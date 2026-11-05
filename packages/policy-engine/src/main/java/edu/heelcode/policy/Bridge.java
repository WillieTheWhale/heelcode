package edu.heelcode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** The only bridge path that may start inference is after a successful admission decision. */
public final class Bridge {
  public record Response(
      String requestId,
      String sessionId,
      int turn,
      String engineSessionId,
      String status,
      boolean inferenceAttempted,
      String text,
      String error,
      Workspace.Response policy) {}

  public record State(
      String workspace,
      String sessionId,
      String assignment,
      String policyDigest,
      String model,
      String engineSessionId,
      String pendingRequest,
      List<Response> responses) {}

  public record Result(String sessionId, String text, String error) {}

  @FunctionalInterface
  public interface Solver {
    Result run(Path workspace, String sessionId, String prompt, Policy.Scope scope, Path run)
        throws Exception;
  }

  /** Observes the admission decision before any inference starts (frontends show it at once). */
  @FunctionalInterface
  public interface Listener {
    void admitted(Workspace.Response admission);
  }

  public static Response chat(
      Path workspace,
      Workspace.Request request,
      Workspace.Extractor extractor,
      String model,
      Solver solver)
      throws Exception {
    return chat(workspace, request, extractor, model, solver, admission -> {});
  }

  public static Response chat(
      Path workspace,
      Workspace.Request request,
      Workspace.Extractor extractor,
      String model,
      Solver solver,
      Listener listener)
      throws Exception {
    var admission = Workspace.check(workspace, request, extractor);
    var root = workspace.toRealPath();
    var directory = root.resolve(".heelcode-policy/chat/" + admission.sessionId());
    Files.createDirectories(directory);
    try (var channel =
            FileChannel.open(
                directory.resolve("chat.lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        var lock = channel.tryLock()) {
      if (lock == null)
        return failure(
            admission, "", "busy", "Inference is busy. Retry the same request ID later.");
      var file = directory.resolve("state.json");
      var state =
          Files.exists(file)
              ? Json.read(file, State.class)
              : new State(
                  root.toString(),
                  admission.sessionId(),
                  request.assignment(),
                  admission.policyDigest(),
                  model,
                  "",
                  "",
                  List.of());
      if (!state.workspace().equals(root.toString())
          || !state.sessionId().equals(admission.sessionId())
          || !state.assignment().equals(request.assignment())
          || !state.policyDigest().equals(admission.policyDigest())
          || !state.model().equals(model))
        return failure(
            admission,
            state.engineSessionId(),
            "error",
            "Chat scope or model changed; start a new chat session.");
      for (var response : state.responses()) {
        if (response.requestId().equals(request.requestId())) return response;
      }
      if (!state.pendingRequest().isEmpty())
        return failure(
            admission,
            state.engineSessionId(),
            "uncertain",
            "An earlier inference has an unresolved outcome. It will not be automatically replayed;"
                + " inspect local chat state.");
      if (admission.turn() != state.responses().size() + 1)
        return failure(
            admission,
            state.engineSessionId(),
            "error",
            "Requests must run in order. Do not mix admission-only sessions with chat sessions.");
      var active = Workspace.active(root);
      if (!active.policyDigest().equals(admission.policyDigest()))
        return failure(
            admission, state.engineSessionId(), "error", "Policy changed before inference.");
      listener.admitted(admission);
      if (!admission.decision().forward()) {
        var response =
            new Response(
                request.requestId(),
                admission.sessionId(),
                admission.turn(),
                state.engineSessionId(),
                "blocked",
                false,
                admission.decision().feedback(),
                "",
                admission);
        save(file, state, response);
        return response;
      }
      // Durable intent precedes the external call. A crash here must never silently retry
      // inference.
      Json.write(
          file,
          new State(
              state.workspace(),
              state.sessionId(),
              state.assignment(),
              state.policyDigest(),
              model,
              state.engineSessionId(),
              request.requestId(),
              state.responses()));
      final Result result;
      try {
        result =
            solver.run(
                root,
                state.engineSessionId(),
                request.prompt(),
                active.policy().scope(request.assignment()),
                directory.resolve("turn-" + admission.turn()));
      } catch (Exception error) {
        // We cannot prove whether the provider accepted the request. Retain the pending marker.
        return new Response(
            request.requestId(),
            admission.sessionId(),
            admission.turn(),
            state.engineSessionId(),
            "uncertain",
            true,
            "",
            "Inference outcome is unknown: " + error.getClass().getSimpleName(),
            admission);
      }
      var current = policyCurrent(root, admission.policyDigest());
      var response =
          new Response(
              request.requestId(),
              admission.sessionId(),
              admission.turn(),
              result.sessionId(),
              result.error().isEmpty() && current ? "completed" : "failed",
              true,
              current ? result.text() : "",
              current ? result.error() : "Policy changed during inference; output withheld.",
              admission);
      save(file, state, response);
      return response;
    }
  }

  private static boolean policyCurrent(Path workspace, String digest) {
    try {
      return Workspace.active(workspace).policyDigest().equals(digest);
    } catch (IOException | IllegalArgumentException error) {
      return false;
    }
  }

  private static void save(Path file, State state, Response response) throws IOException {
    var responses = new ArrayList<>(state.responses());
    responses.add(response);
    Json.write(
        file,
        new State(
            state.workspace(),
            state.sessionId(),
            state.assignment(),
            state.policyDigest(),
            state.model(),
            response.engineSessionId(),
            "",
            List.copyOf(responses)));
  }

  private static Response failure(
      Workspace.Response admission, String engine, String status, String error) {
    return new Response(
        admission.requestId(),
        admission.sessionId(),
        admission.turn(),
        engine,
        status,
        false,
        "",
        error,
        admission);
  }

  public static Solver heelcode(String executable, String model) {
    return heelcode(executable, model, () -> PromptStore.open().text(PromptStore.Kind.SYSTEM));
  }

  /** Everything the answer model reads, in order: the admin system prompt, then the user message. */
  public static String submission(String system, Policy.Scope scope, String prompt)
      throws IOException {
    return PromptStore.normalize(system) + request(scope, prompt);
  }

  /** The user message: the assignment's policy, then the student's request, both as JSON. */
  public static String request(Policy.Scope scope, String prompt) throws IOException {
    return "ASSIGNMENT POLICY JSON:\n"
        + Json.MAPPER.writeValueAsString(scope)
        + "\nSTUDENT REQUEST JSON:\n"
        + Json.MAPPER.writeValueAsString(prompt);
  }

  /** The headless interface a HeelCode launcher speaks: OpenCode v1 or v2. */
  public enum Engine {
    V1,
    V2
  }

  private static final Map<String, Engine> ENGINES = new java.util.concurrent.ConcurrentHashMap<>();

  /** Detected once per launcher; only v2's `run` offers private `--standalone` servers. */
  public static Engine engine(String executable) throws IOException, InterruptedException {
    var known = ENGINES.get(executable);
    if (known != null) return known;
    var help = Files.createTempFile("heelcode-run-help-", ".txt");
    try {
      var process =
          new ProcessBuilder(executable, "run", "--help")
              .redirectErrorStream(true)
              .redirectOutput(help.toFile())
              .start();
      process.getOutputStream().close();
      if (!process.waitFor(60, TimeUnit.SECONDS)) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        throw new IOException("HeelCode did not answer `run --help`");
      }
      var engine = Files.readString(help).contains("--standalone") ? Engine.V2 : Engine.V1;
      ENGINES.put(executable, engine);
      return engine;
    } finally {
      Files.deleteIfExists(help);
    }
  }

  /**
   * The admin system prompt replaces the agent's default coding-assistant system prompt (config
   * `agent.build.prompt`, honored by both engines); the policy and request are the user message.
   * It is read at call time, so a saved admin edit applies to the next turn.
   */
  public static Solver heelcode(String executable, String model, PromptStore.Source system) {
    if (!model.matches("(openai|opencode)/gpt-5\\.6-(luna|terra)"))
      throw new IllegalArgumentException(
          "Inference must explicitly use a Luna/Terra model on OpenAI or OpenCode");
    return (workspace, sessionId, prompt, scope, run) -> {
      Files.createDirectory(run);
      var engine = engine(executable);
      var command =
          new ArrayList<>(
              List.of(
                  executable,
                  "run",
                  engine == Engine.V2 ? "--standalone" : "--pure",
                  "--format",
                  "json",
                  "--model",
                  model,
                  "--agent",
                  "build"));
      if (!sessionId.isEmpty()) command.addAll(List.of("--session", sessionId));
      var instructions = PromptStore.normalize(system.text());
      var submitted = request(scope, prompt);
      Files.writeString(run.resolve("system.txt"), instructions);
      Files.writeString(run.resolve("submitted.txt"), submitted);
      var builder =
          new ProcessBuilder(command)
              .directory(workspace.toFile())
              .redirectOutput(run.resolve("stdout.private.jsonl").toFile())
              .redirectError(run.resolve("stderr.private.txt").toFile());
      var environment = builder.environment();
      // The answer model does not need the classifier's credential.
      environment.remove("TYPESAFE_API_KEY");
      // Pin auxiliary title work too; no expensive implicit model or tool execution in this path.
      environment.put(
          "OPENCODE_CONFIG_CONTENT",
          Json.MAPPER.writeValueAsString(
              Map.of(
                  "model",
                  model,
                  "small_model",
                  model,
                  "permission",
                  Map.of("*", "deny"),
                  "agent",
                  Map.of("build", Map.of("steps", 1, "prompt", instructions)))));
      if (engine == Engine.V2) {
        // v2 has no --pure: an empty config directory and these switches keep the user's
        // plugins, agents, and instruction files out. Logins stay in the data directory.
        environment.put("OPENCODE_CONFIG_DIR", Files.createDirectory(run.resolve("config")).toString());
        environment.put("OPENCODE_DISABLE_CLAUDE_CODE", "1");
        environment.put("OPENCODE_DISABLE_PROJECT_CONFIG", "1");
        // v2 resolves the project from PWD before the process directory.
        environment.put("PWD", workspace.toString());
      }
      var started = Instant.now();
      var process = builder.start();
      try {
        try (var stdin = process.getOutputStream()) {
          stdin.write(submitted.getBytes(StandardCharsets.UTF_8));
        }
        if (!process.waitFor(180, TimeUnit.SECONDS))
          throw new IOException("HeelCode inference timed out");
        if (Files.size(run.resolve("stdout.private.jsonl")) > 4_000_000)
          throw new IOException("Inference output exceeds research limit");
        var lines = Files.readAllLines(run.resolve("stdout.private.jsonl"));
        var events = new ArrayList<JsonNode>();
        for (var line : lines) {
          if (line.isBlank()) continue;
          var event = Json.MAPPER.readTree(line);
          redact(event);
          events.add(event);
        }
        Json.write(run.resolve("events.json"), events);
        Json.write(
            run.resolve("metadata.json"),
            Map.of(
                "model",
                model,
                "engine",
                engine.name(),
                "command",
                command,
                "systemSha256",
                Course.hash(instructions),
                "startedAt",
                started.toString(),
                "elapsedMs",
                java.time.Duration.between(started, Instant.now()).toMillis(),
                "exitCode",
                process.exitValue()));
        var ids =
            events.stream()
                .map(e -> e.path("sessionID").asText())
                .filter(id -> !id.isEmpty())
                .distinct()
                .toList();
        // v2 reports `error.message`; v1 reported `error.data.message`.
        var error =
            events.stream()
                .filter(e -> e.path("type").asText().equals("error"))
                .map(
                    e ->
                        e.path("error")
                            .path("message")
                            .asText(e.path("error").path("data").path("message").asText("Provider error")))
                .findFirst()
                .orElse("");
        if (ids.isEmpty() && !error.isEmpty())
          return new Result(sessionId, "", error);
        if (ids.size() != 1
            || !ids.get(0).matches("ses_[A-Za-z0-9_-]+")
            || (!sessionId.isEmpty() && !sessionId.equals(ids.get(0))))
          throw new IOException("Inference did not return the expected session ID");
        if (process.exitValue() != 0 || !error.isEmpty())
          return new Result(
              ids.get(0), "", error.isEmpty() ? "HeelCode exited unsuccessfully" : error);
        if (events.stream().anyMatch(e -> e.path("type").asText().equals("tool_use")))
          return new Result(
              ids.get(0), "", "Unexpected tool use: this research bridge is text-only");
        var text =
            events.stream()
                .filter(e -> e.path("type").asText().equals("text"))
                .map(e -> e.path("part").path("text").asText())
                .collect(java.util.stream.Collectors.joining("\n"));
        if (text.isBlank()) return new Result(ids.get(0), "", "Inference completed without text");
        return new Result(ids.get(0), text, "");
      } finally {
        if (process.isAlive()) {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroyForcibly();
        }
      }
    };
  }

  private static void redact(JsonNode node) {
    if (node instanceof ObjectNode object) {
      for (var name :
          List.of(
              "requestHeaders",
              "responseHeaders",
              "authorization",
              "cookie",
              "set-cookie",
              "access_token",
              "refresh_token",
              "apiKey")) {
        if (object.has(name)) object.put(name, "[redacted]");
      }
    }
    node.forEach(Bridge::redact);
  }
}

package edu.heelcode.policy;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

public final class Main {
  public static void main(String[] args) {
    try {
      run(args);
    } catch (Exception error) {
      System.err.println(error.getMessage());
      System.exit(1);
    }
  }

  static void run(String[] args) throws Exception {
    if (args.length == 0 || args[0].equals("--help")) {
      System.out.println(
          """
          HeelCode policy engine (Java 17+)
          generate COURSE_ID SOURCE_DIR RUN_DIR MODEL    ingest, generate and validate policy
          validate COURSE_ID SOURCE_DIR RUN_DIR          validate a saved generation response
          activate WORKSPACE SOURCE_DIR RUN_DIR          activate a validated generated policy
          check WORKSPACE MODEL                          one JSON request on stdin, one response
          stdio WORKSPACE MODEL                          persistent JSONL stdin/stdout server
          chat WORKSPACE CLASSIFIER_MODEL INFERENCE_MODEL  persistent gated HeelCode chat
          extract CASES_JSON RUN_DIR MODEL               extract evaluation features, no labels sent
          extract-jev CASES_JSON RUN_DIR                 Jev 1.13 classifier-only research run
          evaluate BUNDLE POLICY CASES FEATURES REPORT   offline decisions and confusion matrix
          score-policy BUNDLE POLICY ORACLE REPORT       independent rule-cell comparison
          schema policy|features                        print model output schema
          Models: gpt-5.6-luna or gpt-5.6-terra. No implicit fallback.
          Classifier only: jev or jev-1.13.0 (requires TYPESAFE_API_KEY).
          Request: {"requestId":"r1","sessionId":"","assignment":"a1","prompt":"Explain fork"}
          Empty sessionId creates a new hps_ session; reuse returned ID for follow-up requests.
          """);
      return;
    }
    switch (args[0]) {
      case "validate" -> {
        require(args, 4);
        var course = Course.ingest(args[1], Path.of(args[2]));
        var run = Path.of(args[3]);
        if (!java.nio.file.Files.readString(run.resolve("prompt.txt"))
            .equals(Prompts.generation(course)))
          throw new IllegalArgumentException(
              "Current sources do not match the saved generation prompt");
        var policy = Json.read(run.resolve("response.json"), Policy.class);
        policy.validate(course);
        Json.write(run.resolve("bundle.json"), course);
        Json.write(run.resolve("policy.json"), policy);
        emit(Map.of("status", "validated-from-saved-response", "digest", course.digest()));
      }
      case "generate" -> {
        require(args, 5);
        var course = Course.ingest(args[1], Path.of(args[2]));
        var run = Path.of(args[3]);
        var result =
            new CodexModel(args[4])
                .generate(Prompts.generation(course), Prompts.policySchema(), run);
        Json.write(run.resolve("bundle.json"), course);
        var policy = Json.MAPPER.treeToValue(result, Policy.class);
        policy.validate(course);
        Json.write(run.resolve("policy.json"), policy);
        emit(Map.of("status", "validated", "run", run.toString(), "digest", course.digest()));
      }
      case "activate" -> {
        require(args, 4);
        var run = Path.of(args[3]);
        Workspace.activate(
            Path.of(args[1]),
            Path.of(args[2]),
            Json.read(run.resolve("bundle.json"), Course.class),
            Json.read(run.resolve("policy.json"), Policy.class));
        emit(Map.of("status", "active", "workspace", Path.of(args[1]).toAbsolutePath().toString()));
      }
      case "check", "stdio", "chat" -> {
        require(args, args[0].equals("chat") ? 4 : 3);
        var workspace = Path.of(args[1]);
        var model = Workspace.model(workspace, args[2]);
        var solver =
            args[0].equals("chat")
                ? Bridge.heelcode(
                    System.getenv().getOrDefault("HEELCODE_EXECUTABLE", "heelcode"), args[3])
                : null;
        if (args[0].equals("check")) {
          var bytes = System.in.readNBytes(128_001);
          if (bytes.length > 128_000) {
            requestError("", new IllegalArgumentException("Request too large (limit 128,000 bytes)"));
            return;
          }
          reply(bytes, workspace, model, "", null);
          return;
        }
        // Newline is the framing boundary, not EOF. Keep stdout strictly one JSON object per line.
        // Bound bytes before decoding, and discard an oversized frame through its boundary.
        try (var reader = new BufferedInputStream(System.in)) {
          var line = new ByteArrayOutputStream();
          var oversized = false;
          int value;
          while ((value = reader.read()) != -1) {
            if (value == '\n') {
              if (oversized)
                requestError("", new IllegalArgumentException("JSONL frame too large (limit 128,000 bytes)"));
              if (!oversized)
                reply(line.toByteArray(), workspace, model, solver == null ? "" : args[3], solver);
              line.reset();
              oversized = false;
              continue;
            }
            if (oversized) continue;
            if (line.size() >= 128_000) {
              oversized = true;
              line.reset();
              continue;
            }
            line.write(value);
          }
          if (oversized)
            requestError("", new IllegalArgumentException("JSONL frame too large (limit 128,000 bytes)"));
          if (line.size() > 0)
            reply(line.toByteArray(), workspace, model, solver == null ? "" : args[3], solver);
        }
      }
      case "extract" -> {
        require(args, 4);
        Evaluation.extract(Path.of(args[1]), Path.of(args[2]), args[3]);
        emit(Map.of("status", "extracted"));
      }
      case "extract-jev" -> {
        require(args, 3);
        JevModel.extract(Path.of(args[1]), Path.of(args[2]));
        emit(Map.of("status", "extracted", "model", JevModel.MODEL));
      }
      case "score-policy" -> {
        require(args, 5);
        var report = Evaluation.scorePolicy(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        Json.write(Path.of(args[4]), report);
        emit(
            Map.of(
                "cells",
                report.get("cells"),
                "correct",
                report.get("correct"),
                "extraScopes",
                report.get("extraScopes")));
      }
      case "evaluate" -> {
        require(args, 6);
        var report =
            Evaluation.score(
                Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
        Json.write(Path.of(args[5]), report);
        emit(
            Map.of(
                "cases",
                report.get("cases"),
                "correct",
                report.get("correct"),
                "unsafeAllows",
                report.get("unsafeAllows"),
                "falseDenials",
                report.get("falseDenials")));
      }
      case "schema" -> {
        require(args, 2);
        emit(args[1].equals("policy") ? Prompts.policySchema() : Prompts.featuresSchema());
      }
      default -> throw new IllegalArgumentException("Unknown command; use --help");
    }
  }

  static void reply(
      byte[] bytes,
      Path workspace,
      Workspace.Extractor model,
      String inferenceModel,
      Bridge.Solver solver)
      throws Exception {
    var requestId = "";
    try {
      var text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      var request = Json.MAPPER.readValue(text, Workspace.Request.class);
      requestId = request.requestId();
      emit(
          solver == null
              ? Workspace.check(workspace, request, model)
              : Bridge.chat(workspace, request, model, inferenceModel, solver));
    } catch (Exception error) {
      requestError(requestId, error);
    }
  }

  private static void requestError(String requestId, Exception error) throws Exception {
    var message =
        error instanceof CharacterCodingException
            ? "Request is not valid UTF-8"
            : error.getMessage() == null ? "Request failed" : error.getMessage();
    System.err.println("Policy request failed: " + message);
    emit(Map.of("requestId", requestId, "error", error.getClass().getSimpleName(), "message", message, "forward", false));
  }

  static void emit(Object value) throws Exception {
    System.out.println(Json.MAPPER.writeValueAsString(value));
  }

  private static void require(String[] args, int count) {
    if (args.length != count)
      throw new IllegalArgumentException("Wrong argument count; use --help");
  }
}

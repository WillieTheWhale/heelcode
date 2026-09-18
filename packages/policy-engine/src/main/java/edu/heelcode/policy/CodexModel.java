package edu.heelcode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Research adapter using the user's existing Codex sign-in; not a university deployment API. */
public final class CodexModel {
  private final String model;

  public CodexModel(String model) {
    if (!List.of("gpt-5.6-luna", "gpt-5.6-terra").contains(model))
      throw new IllegalArgumentException("Only Luna and Terra are enabled for this experiment");
    this.model = model;
  }

  public JsonNode generate(String prompt, JsonNode schema, Path run)
      throws IOException, InterruptedException {
    if (Files.exists(run))
      throw new IllegalArgumentException(
          "Run directory already exists; preserve prior evidence: " + run);
    Files.createDirectories(run);
    Files.writeString(run.resolve("prompt.txt"), prompt);
    Json.write(run.resolve("schema.json"), schema);
    var isolated = Files.createTempDirectory("heelcode-model-");
    var command =
        new ArrayList<>(
            List.of(
                "codex",
                "exec",
                "--ignore-user-config",
                "--ephemeral",
                "--skip-git-repo-check",
                "--sandbox",
                "read-only",
                "--cd",
                isolated.toString(),
                "--model",
                model,
                "--json",
                "-c",
                "model_reasoning_effort=\"low\"",
                "-c",
                "project_doc_max_bytes=0",
                "-c",
                "web_search=\"disabled\""));
    for (var feature :
        List.of(
            "shell_tool",
            "unified_exec",
            "plugins",
            "apps",
            "multi_agent",
            "browser_use",
            "computer_use",
            "image_generation")) {
      command.add("-c");
      command.add("features." + feature + "=false");
    }
    command.addAll(
        List.of(
            "--output-schema",
            run.resolve("schema.json").toAbsolutePath().toString(),
            "--output-last-message",
            run.resolve("response.json").toAbsolutePath().toString(),
            "-"));
    var started = Instant.now();
    var process =
        new ProcessBuilder(command)
            .redirectOutput(run.resolve("events.jsonl").toFile())
            .redirectError(run.resolve("stderr.txt").toFile())
            .start();
    try {
      try (var input = process.getOutputStream()) {
        input.write(prompt.getBytes(StandardCharsets.UTF_8));
      }
      if (!process.waitFor(180, TimeUnit.SECONDS)) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        Json.write(
            run.resolve("metadata.json"),
            Map.of("model", model, "status", "timeout", "startedAt", started.toString()));
        throw new IOException("Model timed out; evidence saved at " + run);
      }
      var events = Files.readAllLines(run.resolve("events.jsonl"));
      var usage = Json.MAPPER.createObjectNode();
      for (var line : events) {
        var event = Json.MAPPER.readTree(line);
        if (event.path("type").asText().equals("turn.completed"))
          usage.setAll((com.fasterxml.jackson.databind.node.ObjectNode) event.path("usage"));
        if (event.path("type").asText().equals("item.completed")
            && !event.path("item").path("type").asText().equals("agent_message"))
          throw new IOException(
              "Unexpected tool/reasoning item in tool-free experiment: "
                  + event.path("item").path("type"));
      }
      Json.write(
          run.resolve("metadata.json"),
          Map.of(
              "model",
              model,
              "effort",
              "low",
              "startedAt",
              started.toString(),
              "elapsedMs",
              java.time.Duration.between(started, Instant.now()).toMillis(),
              "exitCode",
              process.exitValue(),
              "usage",
              usage,
              "promptSha256",
              Course.hash(prompt),
              "adapter",
              "codex-cli",
              "command",
              command));
      if (process.exitValue() != 0)
        throw new IOException("Model failed; see " + run.resolve("stderr.txt"));
      return Json.MAPPER.readTree(Files.readString(run.resolve("response.json")));
    } finally {
      if (process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
      // Do not recursively remove unexpected model-created files.
      try (var entries = Files.list(isolated)) {
        if (entries.findAny().isEmpty()) Files.delete(isolated);
      }
    }
  }
}

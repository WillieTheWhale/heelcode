package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The `prompts` and `demo` commands, through the real launcher process. */
class CliTest {
  @TempDir Path root;

  record Result(int code, String stdout, String stderr) {}

  Result run(String stdin, String... args) throws Exception {
    var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), Main.class.getName()));
    command.addAll(List.of(args));
    var builder = new ProcessBuilder(command).redirectError(root.resolve("stderr.txt").toFile());
    builder.environment().put("HEELCODE_POLICY_HOME", root.resolve("home").toString());
    var process = builder.start();
    try {
      try (var input = process.getOutputStream()) {
        input.write(stdin.getBytes(StandardCharsets.UTF_8));
      }
      var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(process.waitFor(20, TimeUnit.SECONDS));
      return new Result(process.exitValue(), stdout, Files.readString(root.resolve("stderr.txt")));
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void promptsCanBeShownSetFromStdinAuditedAndReset() throws Exception {
    var shown = run("", "prompts", "show", "classifier");
    assertEquals(0, shown.code());
    assertEquals(PromptStore.defaultText(PromptStore.Kind.CLASSIFIER), shown.stdout());

    var set = run("Name the boundary.\nOffer one permitted next step.\n", "prompts", "set", "system", "-", "handout", "prompt", "1");
    assertEquals(0, set.code(), set.stderr());
    var revision = Json.MAPPER.readValue(set.stdout(), PromptStore.Revision.class);
    assertEquals("handout prompt 1", revision.note());
    assertEquals("Name the boundary.\nOffer one permitted next step.\n", run("", "prompts", "show", "system").stdout());

    var history = Json.MAPPER.readTree(run("", "prompts", "history", "system").stdout());
    assertTrue(history.get("custom").asBoolean());
    assertEquals(revision.sha256(), history.get("sha256").asText());
    assertEquals(1, history.get("revisions").size());

    assertEquals(0, run("", "prompts", "reset", "system").code());
    assertEquals(PromptStore.defaultText(PromptStore.Kind.SYSTEM), run("", "prompts", "show", "system").stdout());
    assertEquals(5, Json.MAPPER.readTree(run("", "prompts", "presets", "system").stdout()).size());

    var bad = run("", "prompts", "show", "tutor");
    assertEquals(1, bad.code());
    assertTrue(bad.stderr().contains("use classifier or system"));
  }

  @Test
  void demoCommandActivatesTheSys301Workspace() throws Exception {
    var result = run("", "demo", root.resolve("demo").toString());
    assertEquals(0, result.code(), result.stderr());
    var workspace = Path.of(Json.MAPPER.readTree(result.stdout()).get("workspace").asText());
    assertEquals("sys301", Workspace.active(workspace).course().courseId());
  }
}

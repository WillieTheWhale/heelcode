package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The admin system prompt reaches the real answer-model process as its system prompt, on both
 * OpenCode engines, after admission and only for admitted requests. A stand-in launcher answers
 * `run --help` like v1 or v2 and records its arguments, environment, and stdin.
 */
class AnswerPromptTest {
  @TempDir Path root;

  Path workspace() throws Exception {
    var source = root.resolve("sources");
    Files.createDirectories(source);
    Files.writeString(source.resolve("policy.md"), "Concepts are allowed. Executable tests are prohibited.");
    var course = Course.ingest("course", source);
    var evidence = List.of(new Policy.Evidence("policy.md", course.sources().get(0).text()));
    var rules =
        List.of(
            new Policy.Rule(List.of("test_code"), "deny", false, evidence),
            new Policy.Rule(Policy.ACTIVITIES.stream().filter(a -> !a.equals("test_code")).toList(), "allow", false, evidence));
    var workspace = root.resolve("workspace");
    Workspace.activate(workspace, source, course, new Policy("course", "v1", List.of(new Policy.Scope("*", "Baseline", rules), new Policy.Scope("a1", "Assignment", rules))));
    return workspace.toRealPath();
  }

  /** Answers like `heelcode run --format json`; `v2`/`fail` marker files select its behavior. */
  Path executable(boolean v2) throws Exception {
    var script = root.resolve("fake-heelcode");
    Files.writeString(
        script,
        """
        #!/bin/sh
        dir=$(dirname "$0")
        if [ "${2:-}" = "--help" ]; then
          if [ -f "$dir/v2" ]; then echo "  --standalone  start a private server for this run"; fi
          echo "usage: heelcode run [message..]"
          exit 0
        fi
        printf '%s\\n' "$@" > "$dir/args.txt"
        printf '%s' "$OPENCODE_CONFIG_CONTENT" > "$dir/config.json"
        printf '%s' "${OPENCODE_CONFIG_DIR:-}" > "$dir/configdir.txt"
        printf '%s' "${OPENCODE_DISABLE_CLAUDE_CODE:-}" > "$dir/claude.txt"
        printf '%s' "${PWD:-}" > "$dir/pwd.txt"
        cat > "$dir/stdin.txt"
        if [ -f "$dir/fail" ]; then
          printf '%s\\n' '{"type":"error","timestamp":1,"sessionID":"ses_fake1","error":{"type":"provider.no-route","message":"Model unavailable: openai/gpt-5.6-luna"}}'
          exit 1
        fi
        printf '%s\\n' '{"type":"step_start","sessionID":"ses_fake1","part":{"type":"step-start"}}'
        printf '%s\\n' '{"type":"text","sessionID":"ses_fake1","part":{"type":"text","text":"fork copies; exec replaces."}}'
        """);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
    if (v2) Files.writeString(root.resolve("v2"), "");
    return script;
  }

  Workspace.Extractor extractor(String activity) {
    return (id, prompt, assignment, history) -> new Classifier.Features(id, List.of(activity), false, false, false, "controlled");
  }

  @Test
  void v2RunsStandaloneWithTheAdminPromptAsSystemPromptAndIsolatedConfig() throws Exception {
    var workspace = workspace();
    var launcher = executable(true).toString();
    assertEquals(Bridge.Engine.V2, Bridge.engine(launcher));
    var solver = Bridge.heelcode(launcher, "openai/gpt-5.6-luna", () -> "You are a Socratic SYS 301 tutor.\n");
    var response = Bridge.chat(workspace, new Workspace.Request("r1", "", "a1", "Explain fork versus exec."), extractor("concept"), "openai/gpt-5.6-luna", solver);
    assertEquals("completed", response.status());
    assertEquals("fork copies; exec replaces.", response.text());
    var args = Files.readAllLines(root.resolve("args.txt"));
    assertEquals(List.of("run", "--standalone", "--format", "json", "--model", "openai/gpt-5.6-luna", "--agent", "build"), args);
    var config = Json.MAPPER.readTree(Files.readString(root.resolve("config.json")));
    assertEquals("You are a Socratic SYS 301 tutor.\n", config.path("agent").path("build").path("prompt").asText());
    assertEquals(1, config.path("agent").path("build").path("steps").asInt());
    assertEquals("deny", config.path("permission").path("*").asText());
    var turn = workspace.resolve(".heelcode-policy/chat/" + response.sessionId() + "/turn-1");
    assertEquals(turn.resolve("config").toString(), Files.readString(root.resolve("configdir.txt")));
    try (var entries = Files.list(turn.resolve("config"))) {
      assertTrue(entries.findAny().isEmpty(), "the isolated config directory stays empty");
    }
    assertEquals("1", Files.readString(root.resolve("claude.txt")));
    assertEquals(workspace.toString(), Files.readString(root.resolve("pwd.txt")));
    var stdin = Files.readString(root.resolve("stdin.txt"));
    assertTrue(stdin.startsWith("ASSIGNMENT POLICY JSON:\n"), "the system prompt is not repeated in the user message");
    assertTrue(stdin.endsWith("STUDENT REQUEST JSON:\n\"Explain fork versus exec.\""));
    assertEquals(stdin, Files.readString(turn.resolve("submitted.txt")));
    assertEquals("You are a Socratic SYS 301 tutor.\n", Files.readString(turn.resolve("system.txt")));
    assertEquals("V2", Json.MAPPER.readTree(turn.resolve("metadata.json").toFile()).path("engine").asText());
  }

  @Test
  void v1KeepsThePureFlagAndTheSamePromptDelivery() throws Exception {
    var workspace = workspace();
    var launcher = executable(false).toString();
    var solver = Bridge.heelcode(launcher, "openai/gpt-5.6-luna", () -> "Tutor prompt");
    var response = Bridge.chat(workspace, new Workspace.Request("r1", "", "a1", "Explain fork."), extractor("concept"), "openai/gpt-5.6-luna", solver);
    assertEquals("completed", response.status());
    assertEquals("--pure", Files.readAllLines(root.resolve("args.txt")).get(1));
    assertEquals("Tutor prompt\n", Json.MAPPER.readTree(Files.readString(root.resolve("config.json"))).path("agent").path("build").path("prompt").asText());
    assertEquals("", Files.readString(root.resolve("configdir.txt")));
    assertEquals(Bridge.Engine.V1, Bridge.engine(launcher));
  }

  @Test
  void savedAdminPromptIsReadAtCallTime() throws Exception {
    var previous = System.getProperty("heelcode.policy.home");
    System.setProperty("heelcode.policy.home", root.resolve("home").toString());
    try {
      var workspace = workspace();
      var solver = Bridge.heelcode(executable(true).toString(), "openai/gpt-5.6-luna");
      var first = Bridge.chat(workspace, new Workspace.Request("r1", "", "a1", "Explain fork."), extractor("concept"), "openai/gpt-5.6-luna", solver);
      assertEquals(PromptStore.defaultText(PromptStore.Kind.SYSTEM), prompt());
      PromptStore.open().save(PromptStore.Kind.SYSTEM, "Handout prompt 1: name the boundary, offer one permitted next step.", "test");
      Bridge.chat(workspace, new Workspace.Request("r2", first.sessionId(), "a1", "Explain exec."), extractor("concept"), "openai/gpt-5.6-luna", solver);
      assertEquals("Handout prompt 1: name the boundary, offer one permitted next step.\n", prompt());
      assertTrue(Files.readAllLines(root.resolve("args.txt")).contains("ses_fake1"), "the follow-up continues the engine session");
    } finally {
      if (previous == null) System.clearProperty("heelcode.policy.home");
      else System.setProperty("heelcode.policy.home", previous);
    }
  }

  @Test
  void v2ProviderErrorsAreReportedAsDefinitiveFailures() throws Exception {
    var workspace = workspace();
    var launcher = executable(true);
    Files.writeString(root.resolve("fail"), "");
    var response = Bridge.chat(workspace, new Workspace.Request("r1", "", "a1", "Explain fork."), extractor("concept"), "openai/gpt-5.6-luna", Bridge.heelcode(launcher.toString(), "openai/gpt-5.6-luna", () -> "Tutor"));
    assertEquals("failed", response.status());
    assertEquals("Model unavailable: openai/gpt-5.6-luna", response.error());
    assertTrue(response.inferenceAttempted());
  }

  @Test
  void listenerSeesAdmissionBeforeInferenceAndDeclinesNeverRunTheModel() throws Exception {
    var workspace = workspace();
    var events = new ArrayList<String>();
    Bridge.Solver solver = (dir, session, prompt, scope, run) -> {
      events.add("solver");
      return new Bridge.Result("ses_fake2", "answer", "");
    };
    var allowed = Bridge.chat(workspace, new Workspace.Request("r1", "", "a1", "Explain pipes."), extractor("concept"), "openai/gpt-5.6-luna", solver, admission -> events.add("admitted:" + admission.decision().action()));
    assertEquals(List.of("admitted:allow", "solver"), events);
    assertEquals("completed", allowed.status());
    events.clear();
    var denied = Bridge.chat(workspace, new Workspace.Request("r2", allowed.sessionId(), "a1", "Write runnable tests."), extractor("test_code"), "openai/gpt-5.6-luna", solver, admission -> events.add("admitted:" + admission.decision().action()));
    assertEquals(List.of("admitted:deny"), events);
    assertEquals("blocked", denied.status());
    assertFalse(denied.inferenceAttempted());
    events.clear();
    var replay = Bridge.chat(workspace, new Workspace.Request("r2", allowed.sessionId(), "a1", "Write runnable tests."), extractor("test_code"), "openai/gpt-5.6-luna", solver, admission -> events.add("admitted"));
    assertEquals("blocked", replay.status());
    assertTrue(events.isEmpty(), "an exact replay returns the saved result without re-admitting");
  }

  String prompt() throws Exception {
    return Json.MAPPER.readTree(Files.readString(root.resolve("config.json"))).path("agent").path("build").path("prompt").asText();
  }
}

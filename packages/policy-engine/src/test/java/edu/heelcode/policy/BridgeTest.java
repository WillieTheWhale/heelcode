package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeTest {
  @TempDir Path root;

  Path workspace() throws Exception {
    var source = root.resolve("sources");
    Files.createDirectories(source);
    Files.writeString(
        source.resolve("policy.md"), "Concepts are allowed. Executable tests are prohibited.");
    var course = Course.ingest("course", source);
    var evidence = List.of(new Policy.Evidence("policy.md", course.sources().get(0).text()));
    var rules =
        List.of(
            new Policy.Rule(List.of("test_code"), "deny", false, evidence),
            new Policy.Rule(
                Policy.ACTIVITIES.stream().filter(a -> !a.equals("test_code")).toList(),
                "allow",
                false,
                evidence));
    var policy =
        new Policy(
            "course",
            "v1",
            List.of(
                new Policy.Scope("*", "Baseline", rules),
                new Policy.Scope("a1", "Assignment", rules)));
    var workspace = root.resolve("workspace");
    Workspace.activate(workspace, source, course, policy);
    return workspace;
  }

  Workspace.Extractor extraction(String activity, boolean unclear) {
    return (id, prompt, assignment, history) ->
        new Classifier.Features(
            id,
            activity.isEmpty() ? List.of() : List.of(activity),
            false,
            unclear,
            false,
            "Controlled extraction for bridge contract tests");
  }

  @Test
  void deniedAndUnclearRequestsCannotStartARealProcess() throws Exception {
    var workspace = workspace();
    // A nonexistent executable would fail immediately if admission accidentally invoked it.
    var solver =
        Bridge.heelcode(root.resolve("missing-executable").toString(), "openai/gpt-5.6-luna");
    var denied =
        Bridge.chat(
            workspace,
            new Workspace.Request("r1", "", "a1", "Write tests"),
            extraction("test_code", false),
            "openai/gpt-5.6-luna",
            solver);
    assertEquals("blocked", denied.status());
    assertFalse(denied.inferenceAttempted());
    assertEquals("deny", denied.policy().decision().action());
    var unclear =
        Bridge.chat(
            workspace,
            new Workspace.Request("r2", denied.sessionId(), "a1", "help"),
            extraction("", true),
            "openai/gpt-5.6-luna",
            solver);
    assertEquals("blocked", unclear.status());
    assertFalse(unclear.inferenceAttempted());
    assertEquals(2, unclear.turn());
    assertFalse(
        Files.exists(workspace.resolve(".heelcode-policy/chat/" + denied.sessionId() + "/turn-1")));
  }

  @Test
  void restartReusesEngineIdAndExactRetryNeverCallsSolver() throws Exception {
    var workspace = workspace();
    var calls = new AtomicInteger();
    Bridge.Solver solver =
        (dir, sessionId, prompt, scope, run) -> {
          assertEquals("a1", scope.id());
          assertEquals(calls.get() == 0 ? "" : "ses_test", sessionId);
          calls.incrementAndGet();
          return new Bridge.Result(
              "ses_test", "A controlled test response, not a model result.", "");
        };
    var first =
        Bridge.chat(
            workspace,
            new Workspace.Request("r1", "", "a1", "Explain fork"),
            extraction("concept", false),
            "openai/gpt-5.6-luna",
            solver);
    var request = new Workspace.Request("r2", first.sessionId(), "a1", "Explain it again");
    var second =
        Bridge.chat(
            workspace, request, extraction("concept", false), "openai/gpt-5.6-luna", solver);
    assertEquals("completed", second.status());
    assertEquals("ses_test", second.engineSessionId());
    assertEquals(2, calls.get());
    // Each call reloads state from disk; no process-global in-memory session cache exists.
    assertEquals(
        second,
        Bridge.chat(
            workspace, request, extraction("concept", false), "openai/gpt-5.6-luna", solver));
    assertEquals(2, calls.get());
  }

  @Test
  void uncertainOutcomeIsDurableAndDoesNotReplay() throws Exception {
    var workspace = workspace();
    var calls = new AtomicInteger();
    Bridge.Solver solver =
        (a, b, c, d, e) -> {
          calls.incrementAndGet();
          throw new java.io.IOException("Connection lost after submission");
        };
    var first =
        Bridge.chat(
            workspace,
            new Workspace.Request("r1", "", "a1", "Explain fork"),
            extraction("concept", false),
            "openai/gpt-5.6-luna",
            solver);
    assertEquals("uncertain", first.status());
    assertTrue(first.inferenceAttempted());
    var replay =
        Bridge.chat(
            workspace,
            new Workspace.Request("r1", first.sessionId(), "a1", "Explain fork"),
            extraction("concept", false),
            "openai/gpt-5.6-luna",
            solver);
    assertEquals("uncertain", replay.status());
    assertFalse(replay.inferenceAttempted());
    assertEquals(1, calls.get());
    var next =
        Bridge.chat(
            workspace,
            new Workspace.Request("r2", first.sessionId(), "a1", "Explain more"),
            extraction("concept", false),
            "openai/gpt-5.6-luna",
            solver);
    assertEquals("uncertain", next.status());
    assertEquals(1, calls.get());
  }

  @Test
  void definitiveProviderFailureKeepsEngineSessionAndReplaysFailure() throws Exception {
    var workspace = workspace();
    var calls = new AtomicInteger();
    Bridge.Solver solver =
        (a, b, c, d, e) -> {
          calls.incrementAndGet();
          return new Bridge.Result("ses_failed", "", "Provider rejected request");
        };
    var first =
        Bridge.chat(
            workspace,
            new Workspace.Request("r1", "", "a1", "Explain fork"),
            extraction("concept", false),
            "openai/gpt-5.6-luna",
            solver);
    assertEquals("failed", first.status());
    assertEquals("ses_failed", first.engineSessionId());
    assertEquals(
        first,
        Bridge.chat(
            workspace,
            new Workspace.Request("r1", first.sessionId(), "a1", "Explain fork"),
            extraction("concept", false),
            "openai/gpt-5.6-luna",
            solver));
    assertEquals(1, calls.get());
  }

  @Test
  void policyChangeDuringInferenceWithholdsOutputButKeepsEngineIdentity() throws Exception {
    var workspace = workspace();
    Bridge.Solver solver =
        (a, b, c, d, e) -> {
          Files.writeString(root.resolve("sources/policy.md"), "Instructor changed the policy.");
          return new Bridge.Result("ses_changed", "Do not release this stale-policy answer.", "");
        };
    var response =
        Bridge.chat(
            workspace,
            new Workspace.Request("r1", "", "a1", "Explain fork"),
            extraction("concept", false),
            "openai/gpt-5.6-luna",
            solver);
    assertEquals("failed", response.status());
    assertEquals("", response.text());
    assertEquals("ses_changed", response.engineSessionId());
    assertTrue(response.error().contains("Policy changed"));
  }

  @Test
  void modelSwitchAndMixingAdmissionOnlyHistoryAreRefused() throws Exception {
    var workspace = workspace();
    var solver = Bridge.heelcode(root.resolve("missing").toString(), "openai/gpt-5.6-luna");
    var denied =
        Bridge.chat(
            workspace,
            new Workspace.Request("r1", "", "a1", "Write tests"),
            extraction("test_code", false),
            "openai/gpt-5.6-luna",
            solver);
    var switched =
        Bridge.chat(
            workspace,
            new Workspace.Request("r2", denied.sessionId(), "a1", "Write tests"),
            extraction("test_code", false),
            "openai/gpt-5.6-terra",
            solver);
    assertEquals("error", switched.status());
    assertFalse(switched.inferenceAttempted());
    var gateOnly =
        Workspace.check(
            workspace,
            new Workspace.Request("gate", "", "a1", "Explain fork"),
            extraction("concept", false));
    var mixed =
        Bridge.chat(
            workspace,
            new Workspace.Request("r2", gateOnly.sessionId(), "a1", "Write tests"),
            extraction("test_code", false),
            "openai/gpt-5.6-luna",
            solver);
    assertEquals("error", mixed.status());
    assertFalse(mixed.inferenceAttempted());
  }
}

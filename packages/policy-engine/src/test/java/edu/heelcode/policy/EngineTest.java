package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTest {
  @TempDir Path root;

  Course course() throws Exception {
    Files.createDirectories(root.resolve("sources"));
    Files.writeString(
        root.resolve("sources/policy.md"),
        "Concept explanations are allowed. Executable tests are forbidden. Debugging requires an"
            + " attempt.");
    return Course.ingest("test-course", root.resolve("sources"));
  }

  Policy policy(Course course) {
    var evidence = List.of(new Policy.Evidence("policy.md", course.sources().get(0).text()));
    var rules =
        List.of(
            new Policy.Rule(List.of("test_code"), "deny", false, evidence),
            new Policy.Rule(List.of("debug"), "allow", true, evidence),
            new Policy.Rule(
                Policy.ACTIVITIES.stream()
                    .filter(a -> !List.of("test_code", "debug").contains(a))
                    .toList(),
                "allow",
                false,
                evidence));
    return new Policy(
        "test-course",
        "v1",
        List.of(
            new Policy.Scope("*", "Baseline", rules), new Policy.Scope("a1", "Assignment", rules)));
  }

  Classifier.Features features(
      String id, List<String> activities, boolean attempt, boolean unclear, boolean dishonest) {
    return new Classifier.Features(
        id, activities, attempt, unclear, dishonest, "Test extraction boundary");
  }

  @Test
  void ingestionIsStableAndChangesWithSource() throws Exception {
    var first = course();
    assertEquals(first.digest(), Course.ingest("test-course", root.resolve("sources")).digest());
    Files.writeString(root.resolve("sources/policy.md"), "A different policy.");
    assertNotEquals(first.digest(), Course.ingest("test-course", root.resolve("sources")).digest());
  }

  @Test
  void refusesUnsupportedSourcesAndLinks() throws Exception {
    course();
    Files.writeString(root.resolve("sources/extra.pdf"), "not actually a PDF");
    assertThrows(
        IllegalArgumentException.class,
        () -> Course.ingest("test-course", root.resolve("sources")));
    Files.delete(root.resolve("sources/extra.pdf"));
    Files.createSymbolicLink(root.resolve("sources/link.md"), root.resolve("sources/policy.md"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Course.ingest("test-course", root.resolve("sources")));
  }

  @Test
  void citationsMustExistVerbatimAndRulesMustBeComplete() throws Exception {
    var course = course();
    policy(course).validate(course);
    var rule =
        new Policy.Rule(
            Policy.ACTIVITIES,
            "allow",
            false,
            List.of(new Policy.Evidence("policy.md", "This is a fabricated source quote.")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Policy(
                    "test-course", "v1", List.of(new Policy.Scope("*", "Baseline", List.of(rule))))
                .validate(course));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Policy("test-course", "v1", List.of(new Policy.Scope("*", "Baseline", List.of())))
                .validate(course));
  }

  @Test
  void mixedScopeCannotHideForbiddenOutput() throws Exception {
    var decision =
        Classifier.decide(
            policy(course()),
            "a1",
            features("r1", List.of("concept", "test_code"), true, false, false));
    assertEquals("deny", decision.action());
    assertFalse(decision.forward());
    assertFalse(decision.evidence().isEmpty());
  }

  @Test
  void uncertaintyAndMissingAttemptClarifyWithoutAccusation() throws Exception {
    var policy = policy(course());
    assertEquals(
        "clarify",
        Classifier.decide(policy, "a1", features("r1", List.of(), false, true, false)).action());
    assertEquals(
        "attempt_required",
        Classifier.decide(policy, "a1", features("r1", List.of("debug"), false, false, false))
            .reason());
    assertEquals(
        "allow",
        Classifier.decide(policy, "a1", features("r1", List.of("debug"), true, false, false))
            .action());
    assertEquals(
        "unknown_assignment",
        Classifier.decide(
                policy, "unrecognized", features("r1", List.of("concept"), false, false, false))
            .reason());
  }

  @Test
  void deceptionGateAndMalformedFeaturesFailClosed() throws Exception {
    assertEquals(
        "deny",
        Classifier.decide(
                policy(course()), "a1", features("r1", List.of("concept"), false, false, true))
            .action());
    assertThrows(
        IllegalArgumentException.class,
        () -> features("r1", List.of("made_up"), false, false, false).validate());
    assertThrows(
        IllegalArgumentException.class,
        () -> features("r1", List.of(), false, false, false).validate());
  }

  @Test
  void sessionSurvivesReloadAndExactRetryDoesNotAppend() throws Exception {
    var course = course();
    var workspace = root.resolve("workspace");
    Workspace.activate(workspace, root.resolve("sources"), course, policy(course));
    var first =
        Workspace.check(
            workspace,
            new Workspace.Request("r1", "", "a1", "Explain pointers"),
            (id, prompt, assignment, history) ->
                features(id, List.of("concept"), false, false, false));
    assertEquals(1, first.turn());
    var next = new Workspace.Request("r2", first.sessionId(), "a1", "Explain it again");
    var second =
        Workspace.check(
            workspace,
            next,
            (id, prompt, assignment, history) -> {
              assertEquals("Explain pointers", history.get(0).prompt());
              return features(id, List.of("concept"), false, false, false);
            });
    assertEquals(2, second.turn());
    assertEquals(first.sessionId(), second.sessionId());
    assertEquals(
        second,
        Workspace.check(
            workspace,
            next,
            (id, prompt, assignment, history) -> {
              throw new AssertionError("Retry must not call model");
            }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workspace.check(
                workspace,
                new Workspace.Request("r2", first.sessionId(), "a1", "different content"),
                (id, prompt, assignment, history) -> null));
  }

  @Test
  void sessionsCannotCrossWorkspaceAssignmentOrPolicyRevision() throws Exception {
    var course = course();
    var workspace = root.resolve("workspace");
    Workspace.activate(workspace, root.resolve("sources"), course, policy(course));
    Workspace.Extractor extractor =
        (id, prompt, assignment, history) -> features(id, List.of("concept"), false, false, false);
    var first =
        Workspace.check(
            workspace, new Workspace.Request("r1", "", "a1", "Explain pointers"), extractor);
    var secondWorkspace = root.resolve("other");
    Workspace.activate(secondWorkspace, root.resolve("sources"), course, policy(course));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workspace.check(
                secondWorkspace,
                new Workspace.Request("r2", first.sessionId(), "a1", "Explain more"),
                extractor));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workspace.check(
                workspace,
                new Workspace.Request("r2", first.sessionId(), "*", "Explain more"),
                extractor));
    Files.writeString(root.resolve("sources/policy.md"), "Changed instructor policy.");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workspace.check(
                workspace,
                new Workspace.Request("r2", first.sessionId(), "a1", "Explain more"),
                extractor));
  }

  @Test
  void invalidSessionIdAndModelFailureCannotAdmitPrompt() throws Exception {
    var course = course();
    var workspace = root.resolve("workspace");
    Workspace.activate(workspace, root.resolve("sources"), course, policy(course));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workspace.check(
                workspace,
                new Workspace.Request("r1", "../../elsewhere", "a1", "Explain pointers"),
                (a, b, c, d) -> null));
    assertThrows(
        java.io.IOException.class,
        () ->
            Workspace.check(
                workspace,
                new Workspace.Request("r1", "", "a1", "Explain pointers"),
                (a, b, c, d) -> {
                  throw new java.io.IOException("Provider unavailable");
                }));
    try (var files = Files.list(workspace.resolve(".heelcode-policy/sessions"))) {
      assertTrue(files.noneMatch(p -> p.toString().endsWith(".json")));
    }
  }

  @Test
  void changedSourcesDuringInferenceCannotAdmitPrompt() throws Exception {
    var course = course();
    var workspace = root.resolve("workspace");
    Workspace.activate(workspace, root.resolve("sources"), course, policy(course));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Workspace.check(
                workspace,
                new Workspace.Request("r1", "", "a1", "Explain pointers"),
                (id, prompt, assignment, history) -> {
                  Files.writeString(
                      root.resolve("sources/policy.md"), "Policy changed during classification.");
                  return features(id, List.of("concept"), false, false, false);
                }));
    try (var files = Files.list(workspace.resolve(".heelcode-policy/sessions"))) {
      assertTrue(files.noneMatch(p -> p.toString().endsWith(".json")));
    }
  }

  @Test
  void ruleScoringCoversEveryActivityAndMissingScopes() throws Exception {
    var course = course();
    var allowed = Policy.ACTIVITIES.stream().filter(a -> !a.equals("test_code")).toList();
    var oracle =
        new Evaluation.Oracle(
            List.of(
                new Evaluation.ExpectedScope("*", allowed, List.of("debug")),
                new Evaluation.ExpectedScope("a1", allowed, List.of("debug")),
                new Evaluation.ExpectedScope("missing", allowed, List.of("debug"))));
    Json.write(root.resolve("bundle.json"), course);
    Json.write(root.resolve("policy.json"), policy(course));
    Json.write(root.resolve("oracle.json"), oracle);
    var report =
        Evaluation.scorePolicy(
            root.resolve("bundle.json"), root.resolve("policy.json"), root.resolve("oracle.json"));
    assertEquals(30, report.get("cells"));
    assertEquals(20L, report.get("correct"));
  }

  @Test
  void strictJsonRejectsUnknownFieldsAndMissingValues() {
    assertThrows(
        Exception.class,
        () -> Json.MAPPER.readValue("{\"requestId\":\"r1\"}", Workspace.Request.class));
    assertThrows(Exception.class, () -> Json.MAPPER.readTree("{\"a\":1,\"a\":2}"));
    assertThrows(Exception.class, () -> Json.MAPPER.readTree("{} {}"));
    assertThrows(IllegalArgumentException.class, () -> new CodexModel("gpt-6-astra"));
  }
}

package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Rejections and policy enforcement, offline. Recorded and gold features isolate the policy
 * controller: a failure here is a policy or gate bug, never classifier noise.
 */
class RejectionTest {
  static Map<String, TestBench.Suite> suites() throws Exception {
    var result = new java.util.LinkedHashMap<String, TestBench.Suite>();
    for (var suite : TestBench.suites()) result.put(suite.id(), suite);
    return result;
  }

  @Test
  void redTeamSuiteDeclinesEveryAdversarialRequestAndKeepsBenignControls() throws Exception {
    var suite = suites().get("redteam");
    var report = TestBench.run(suite, TestBench.RECORDED, null, progress -> {});
    assertEquals(24, report.cases());
    assertEquals(24, report.correct(), () -> failures(report));
    assertEquals(0, report.unsafeAllows());
    assertEquals(0, report.falseDenials());
    assertEquals(14, report.confusion().get("deny->deny"));
    assertEquals(7, report.confusion().get("allow->allow"));
    assertEquals(3, report.confusion().get("clarify->clarify"));
    for (var row : report.rows()) {
      assertEquals(row.actual().equals("allow"), row.decision().forward(), row.id());
      if (row.features().dishonest()) assertEquals("deception", row.decision().reason(), row.id());
      if (!row.decision().forward()) assertFalse(row.decision().feedback().isBlank(), row.id());
    }
  }

  @Test
  void recordedSeptemberLunaFeaturesStillReproduceAll68Decisions() throws Exception {
    var all = suites();
    var total = 0;
    for (var id : List.of("original", "challenge", "bookkeeping")) {
      var report = TestBench.run(all.get(id), TestBench.RECORDED, null, progress -> {});
      assertEquals(report.cases(), report.correct(), () -> failures(report));
      assertEquals(0, report.unsafeAllows(), id);
      total += report.correct();
    }
    assertEquals(68, total, "matches the 68/68 revised-Luna result in the September 18 report");
  }

  @Test
  void curriculumSuiteScoresAll512AuthoredDecisionsAgainstEachCoursePolicy() throws Exception {
    var report = TestBench.run(suites().get("curriculum"), TestBench.RECORDED, null, progress -> {});
    assertEquals(512, report.cases());
    assertEquals(512, report.correct(), () -> failures(report));
    assertEquals(0, report.unsafeAllows());
    assertEquals(0, report.falseDenials());
  }

  /** Every scope × activity × attempt cell of every packaged policy decides exactly as written. */
  @Test
  void everyPolicyCellDecidesAsWritten() throws Exception {
    var cells = 0;
    for (var policy : policies()) {
      for (var scope : policy.scopes()) {
        for (var activity : Policy.ACTIVITIES) {
          var rule = PolicyReview.cell(policy, scope.id(), activity);
          for (var attempt : List.of(false, true)) {
            var decision = Classifier.decide(policy, scope.id(), features(List.of(activity), attempt, false, false));
            var where = policy.courseId() + "/" + scope.id() + "/" + activity + "/attempt=" + attempt;
            switch (rule.effect()) {
              case "deny" -> {
                assertEquals("deny", decision.action(), where);
                assertEquals("policy_prohibits_activity", decision.reason(), where);
                assertFalse(decision.evidence().isEmpty(), where + " cites the rule");
              }
              case "clarify" -> assertEquals("policy_uncertain", decision.reason(), where);
              default -> {
                var gated = rule.requireAttempt() && !attempt;
                assertEquals(gated ? "clarify" : "allow", decision.action(), where);
                assertEquals(gated ? "attempt_required" : "policy_allows_activity", decision.reason(), where);
              }
            }
            assertEquals(decision.action().equals("allow"), decision.forward(), where);
            cells++;
          }
        }
      }
    }
    assertTrue(cells > 3_500, "swept " + cells + " cells");
  }

  /** Deception, any denied activity, ambiguity, and unknown scopes can never be forwarded. */
  @Test
  void rejectionPrecedenceHoldsInEveryScope() throws Exception {
    for (var policy : policies()) {
      for (var scope : policy.scopes()) {
        var allowed = new ArrayList<String>();
        var denied = new ArrayList<String>();
        for (var activity : Policy.ACTIVITIES) {
          var rule = PolicyReview.cell(policy, scope.id(), activity);
          if (rule.effect().equals("allow") && !rule.requireAttempt()) allowed.add(activity);
          if (rule.effect().equals("deny")) denied.add(activity);
        }
        var where = policy.courseId() + "/" + scope.id();
        for (var activity : allowed) {
          var deception = Classifier.decide(policy, scope.id(), features(List.of(activity), true, false, true));
          assertEquals("deception", deception.reason(), where);
          assertFalse(deception.forward(), where);
          var unclear = Classifier.decide(policy, scope.id(), features(List.of(activity), true, true, false));
          assertEquals("clarify", unclear.action(), where);
          for (var forbidden : denied) {
            var mixed = Classifier.decide(policy, scope.id(), features(List.of(activity, forbidden), true, false, false));
            assertEquals("deny", mixed.action(), where + " " + activity + "+" + forbidden);
          }
        }
        var empty = Classifier.decide(policy, scope.id(), features(List.of(), false, true, false));
        assertEquals("clarify", empty.action(), where);
      }
      var unknown = Classifier.decide(policy, "no-such-assignment", features(List.of("concept"), false, false, false));
      assertEquals("unknown_assignment", unknown.reason());
      assertFalse(unknown.forward());
    }
  }

  static List<Policy> policies() throws Exception {
    var result = new ArrayList<Policy>();
    for (var suite : TestBench.suites()) {
      for (var part : suite.parts()) {
        var policy = Json.read(part.policy(), Policy.class);
        policy.validate(Json.read(part.bundle(), Course.class));
        if (result.stream().noneMatch(p -> p.courseId().equals(policy.courseId()))) result.add(policy);
      }
    }
    assertEquals(33, result.size(), "SYS 301 plus the 32 curriculum courses");
    return result;
  }

  static Classifier.Features features(List<String> activities, boolean attempt, boolean unclear, boolean dishonest) {
    return new Classifier.Features("case", activities, attempt, unclear, dishonest, "Controlled feature vector");
  }

  static String failures(TestBench.Report report) {
    return report.rows().stream()
        .filter(row -> !row.correct())
        .map(row -> row.id() + ": expected " + row.expected() + ", got " + row.actual())
        .toList()
        .toString();
  }
}

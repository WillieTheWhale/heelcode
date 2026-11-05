package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generator's review-and-activate path on the real Luna-generated SYS 301 draft: instructor
 * edits keep the policy valid, are recorded beside the evidence, and actually change decisions.
 */
class GeneratorReviewTest {
  @TempDir Path root;

  static Course bundle() throws Exception {
    return Json.read(Fixtures.sys301Run().resolve("bundle.json"), Course.class);
  }

  static Policy draft() throws Exception {
    return Json.read(Fixtures.sys301Run().resolve("policy.json"), Policy.class);
  }

  @Test
  void savedDraftStillMatchesTheFixtureSources() throws Exception {
    var course = bundle();
    assertEquals(course, Course.ingest("sys301", Fixtures.sys301Sources()), "sources unchanged since generation");
    draft().validate(course);
  }

  @Test
  void instructorEditRegroupsRulesKeepsCitationsAndChangesTheDecision() throws Exception {
    var course = bundle();
    var generated = draft();
    assertEquals("deny", PolicyReview.cell(generated, "a1", "test_code").effect());
    var reviewed = PolicyReview.edit(generated, "a1", "test_code", "allow", false);
    reviewed.validate(course);
    assertEquals("allow", PolicyReview.cell(reviewed, "a1", "test_code").effect());
    assertEquals(PolicyReview.cell(generated, "a1", "test_code").evidence(), PolicyReview.cell(reviewed, "a1", "test_code").evidence());
    assertEquals(List.of(new PolicyReview.Change("a1", "test_code", "deny", false, "allow", false)), PolicyReview.diff(generated, reviewed));
    var features = new Classifier.Features("r", List.of("test_code"), false, false, false, "controlled");
    assertEquals("deny", Classifier.decide(generated, "a1", features).action());
    assertEquals("allow", Classifier.decide(reviewed, "a1", features).action());
    // Untouched scopes are byte-identical.
    assertEquals(generated.scope("a2"), reviewed.scope("a2"));
    var back = PolicyReview.edit(reviewed, "a1", "test_code", "deny", false);
    assertTrue(PolicyReview.diff(generated, back).isEmpty());
    back.validate(course);
  }

  @Test
  void attemptRequirementOnlyAttachesToAllowedHelp() throws Exception {
    var generated = draft();
    var denied = PolicyReview.edit(generated, "a3", "debug", "deny", true);
    assertFalse(PolicyReview.cell(denied, "a3", "debug").requireAttempt());
    var gated = PolicyReview.edit(generated, "practice", "debug", "allow", true);
    assertTrue(PolicyReview.cell(gated, "practice", "debug").requireAttempt());
    var unproven = new Classifier.Features("r", List.of("debug"), false, false, false, "controlled");
    assertEquals("attempt_required", Classifier.decide(gated, "practice", unproven).reason());
    assertThrows(IllegalArgumentException.class, () -> PolicyReview.edit(generated, "a1", "debug", "maybe", false));
    assertThrows(IllegalArgumentException.class, () -> PolicyReview.edit(generated, "a9", "debug", "allow", false));
  }

  @Test
  void reviewIsSavedBesideTheUntouchedDraftAndTheEditedPolicyGatesRequests() throws Exception {
    var run = root.resolve("run");
    Files.createDirectories(run);
    Files.copy(Fixtures.sys301Run().resolve("bundle.json"), run.resolve("bundle.json"));
    Files.copy(Fixtures.sys301Run().resolve("policy.json"), run.resolve("policy.json"));
    var original = Files.readString(run.resolve("policy.json"));
    var generated = draft();
    var reviewed = PolicyReview.edit(generated, "a2", "hint", "deny", false);
    var directory = PolicyReview.save(run, generated, reviewed, "Dr. Example: hints give away the a2 invariants");
    assertEquals(original, Files.readString(run.resolve("policy.json")), "generated evidence is never overwritten");
    var record = Json.read(directory.resolve("review.json"), PolicyReview.Record.class);
    assertEquals(1, record.changes().size());
    assertEquals(Course.hash(Json.MAPPER.writeValueAsString(reviewed)), record.reviewedPolicySha256());
    assertEquals(reviewed, Json.read(directory.resolve("policy.json"), Policy.class));

    var workspace = root.resolve("workspace");
    Workspace.activate(workspace, Fixtures.sys301Sources(), bundle(), reviewed);
    Workspace.Extractor hint = (id, prompt, assignment, history) -> new Classifier.Features(id, List.of("hint"), false, false, false, "controlled");
    assertEquals("deny", Workspace.check(workspace, new Workspace.Request("r1", "", "a2", "Hint please"), hint).decision().action());
    assertEquals("allow", Workspace.check(workspace, new Workspace.Request("r2", "", "a1", "Hint please"), hint).decision().action());
  }

  @Test
  void demoWorkspaceActivatesTheSavedPolicy() throws Exception {
    var workspace = Fixtures.createDemo(root.resolve("demo"));
    var active = Workspace.active(workspace);
    assertEquals("sys301", active.course().courseId());
    assertEquals(draft(), active.policy());
    assertTrue(Files.exists(workspace.resolve("README.md")));
  }
}

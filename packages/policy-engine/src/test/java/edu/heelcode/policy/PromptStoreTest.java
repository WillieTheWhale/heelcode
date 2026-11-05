package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Admin-editable prompts: frozen defaults, versioned saves, and that edits reach the models. */
class PromptStoreTest {
  @TempDir Path home;

  static final Path EXPERIMENTS = Path.of("../../docs/research/experiments/2026-09-18");

  @Test
  void classifierDefaultIsTheFrozenRevisedSeptemberPrompt() throws Exception {
    var saved = EXPERIMENTS.resolve("luna-features-02/prompt.txt");
    assumeTrue(Files.exists(saved), "research evidence is only present in a full checkout");
    var text = Files.readString(saved);
    var instructions = text.substring(0, text.indexOf("REQUESTS JSON:\n"));
    assertEquals(instructions, PromptStore.defaultText(PromptStore.Kind.CLASSIFIER));
    // The other revised runs used the same text, so default runs stay comparable to them.
    for (var run : List.of("luna-bookkeeping-01", "luna-challenge-02", "terra-features-03")) {
      var other = Files.readString(EXPERIMENTS.resolve(run + "/prompt.txt"));
      assertTrue(other.startsWith(instructions + "REQUESTS JSON:\n"), run);
    }
    var first = Files.readString(EXPERIMENTS.resolve("luna-features-01/prompt.txt"));
    var preset =
        PromptStore.presets(PromptStore.Kind.CLASSIFIER).stream()
            .filter(p -> p.id().equals("classifier-first-pass"))
            .findFirst()
            .orElseThrow();
    assertEquals(first.substring(0, first.indexOf("REQUESTS JSON:\n")), PromptStore.presetText(preset));
  }

  @Test
  void systemDefaultReproducesTheSavedBridgeSubmissionByteForByte() throws Exception {
    var saved = EXPERIMENTS.resolve("bridge-full-01/chat/turn-1/submitted.txt");
    assumeTrue(Files.exists(saved), "research evidence is only present in a full checkout");
    var text = Files.readString(saved);
    var scopeStart = text.indexOf("ASSIGNMENT POLICY JSON:\n") + "ASSIGNMENT POLICY JSON:\n".length();
    var requestMarker = text.indexOf("\nSTUDENT REQUEST JSON:\n");
    var scope = Json.MAPPER.readValue(text.substring(scopeStart, requestMarker), Policy.Scope.class);
    var prompt = Json.MAPPER.readValue(text.substring(requestMarker + "\nSTUDENT REQUEST JSON:\n".length()), String.class);
    assertEquals(text, Bridge.submission(PromptStore.defaultText(PromptStore.Kind.SYSTEM), scope, prompt));
  }

  @Test
  void saveCreatesVersionedOverrideAndDefaultRemovesIt() throws Exception {
    var store = new PromptStore(home);
    var kind = PromptStore.Kind.SYSTEM;
    assertFalse(store.custom(kind));
    assertEquals(PromptStore.defaultText(kind), store.text(kind));

    var first = store.save(kind, "Be a Socratic tutor.\r\nNever write the solution.   \n\n\n", "first");
    assertTrue(store.custom(kind));
    assertEquals("Be a Socratic tutor.\nNever write the solution.\n", store.text(kind));
    assertEquals(Course.hash(store.text(kind)), first.sha256());
    assertEquals("save", first.action());

    var second = store.save(kind, "Explain concepts; refuse protected solutions.", "second");
    assertEquals(List.of(second, first), store.history(kind));
    assertEquals("Be a Socratic tutor.\nNever write the solution.\n", store.revision(kind, first.sha256()));

    var restored = store.restoreDefault(kind);
    assertEquals("default", restored.action());
    assertFalse(store.custom(kind));
    assertEquals(PromptStore.defaultText(kind), store.text(kind));
    assertEquals(PromptStore.defaultText(kind), store.revision(kind, restored.sha256()));
    assertEquals(3, store.history(kind).size());
    assertTrue(store.history(PromptStore.Kind.CLASSIFIER).isEmpty(), "history is per prompt");
  }

  @Test
  void rejectsEmptyOversizedAndNulPrompts() {
    var store = new PromptStore(home);
    assertThrows(IllegalArgumentException.class, () -> store.save(PromptStore.Kind.CLASSIFIER, "  \n\t ", ""));
    assertThrows(IllegalArgumentException.class, () -> store.save(PromptStore.Kind.CLASSIFIER, "x".repeat(PromptStore.LIMIT), ""));
    assertThrows(IllegalArgumentException.class, () -> store.save(PromptStore.Kind.CLASSIFIER, "a\0b", ""));
    assertThrows(IllegalArgumentException.class, () -> store.revision(PromptStore.Kind.CLASSIFIER, "../../etc/passwd"));
    assertFalse(Files.exists(home.resolve("prompts/classifier.txt")));
  }

  @Test
  void lintFlagsMissingGuidanceButAcceptsTheDefaults() {
    assertTrue(PromptStore.lint(PromptStore.Kind.CLASSIFIER, PromptStore.defaultText(PromptStore.Kind.CLASSIFIER)).isEmpty());
    assertTrue(PromptStore.lint(PromptStore.Kind.SYSTEM, PromptStore.defaultText(PromptStore.Kind.SYSTEM)).isEmpty());
    var warnings = PromptStore.lint(PromptStore.Kind.CLASSIFIER, "Classify concept and hint requests.");
    assertTrue(warnings.stream().anyMatch(w -> w.contains("'full_solution'")));
    assertTrue(warnings.stream().anyMatch(w -> w.contains("'dishonest'")));
    assertTrue(warnings.stream().anyMatch(w -> w.contains("untrusted")));
    assertTrue(PromptStore.lint(PromptStore.Kind.SYSTEM, "Be kind.").stream().anyMatch(w -> w.contains("policy")));
  }

  @Test
  void everyPresetIsPackagedIncludingTheFourHandoutPrompts() {
    var system = PromptStore.presets(PromptStore.Kind.SYSTEM);
    assertEquals(5, system.size());
    assertEquals(4, system.stream().filter(p -> p.id().startsWith("system-")).count());
    for (var preset : system) assertFalse(PromptStore.presetText(preset).isBlank(), preset.id());
    assertTrue(PromptStore.presetText(system.get(1)).startsWith("You are Heelcode, a tutor for programming assignments."));
    assertEquals(2, PromptStore.presets(PromptStore.Kind.CLASSIFIER).size());
  }

  @Test
  void savedClassifierPromptIsWhatTheModelReceives() throws Exception {
    var previous = System.getProperty("heelcode.policy.home");
    System.setProperty("heelcode.policy.home", home.toString());
    try {
      var inputs = List.of(Map.of("id", "r1", "prompt", "Explain fork.", "assignment", "a1", "history", List.of()));
      assertTrue(Prompts.classification(inputs).startsWith(PromptStore.defaultText(PromptStore.Kind.CLASSIFIER) + "REQUESTS JSON:\n"));
      PromptStore.open().save(PromptStore.Kind.CLASSIFIER, "CUSTOM EXTRACTION RULES", "test");
      var prompt = Prompts.classification(inputs);
      assertTrue(prompt.startsWith("CUSTOM EXTRACTION RULES\nREQUESTS JSON:\n"));
      assertTrue(prompt.endsWith(Json.MAPPER.writeValueAsString(inputs)));
    } finally {
      if (previous == null) System.clearProperty("heelcode.policy.home");
      else System.setProperty("heelcode.policy.home", previous);
    }
  }
}

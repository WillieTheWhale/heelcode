package edu.heelcode.policy;

import java.util.HashSet;
import java.util.List;

public final class Classifier {
  public record Features(
      String id,
      List<String> activities,
      boolean hasAttempt,
      boolean unclear,
      boolean dishonest,
      String rationale) {
    public void validate() {
      if (id.isBlank()
          || rationale.isBlank()
          || !Policy.ACTIVITIES.containsAll(activities)
          || new HashSet<>(activities).size() != activities.size()
          || (activities.isEmpty() && !unclear && !dishonest))
        throw new IllegalArgumentException("Invalid classifier features");
    }
  }

  public record Batch(List<Features> items) {}

  public record Decision(
      String action,
      boolean forward,
      String reason,
      String feedback,
      List<Policy.Evidence> evidence) {}

  public static Decision decide(Policy policy, String assignment, Features features) {
    features.validate();
    var scope = policy.scope(assignment);
    if (scope == null)
      return new Decision(
          "clarify",
          false,
          "unknown_assignment",
          "Select a known assignment in this course workspace.",
          List.of());
    if (features.dishonest())
      return new Decision(
          "deny",
          false,
          "deception",
          "I cannot help conceal AI use or misrepresent authorship. Ask for transparent learning"
              + " assistance instead.",
          List.of());
    var selected =
        scope.rules().stream()
            .filter(r -> r.activities().stream().anyMatch(features.activities()::contains))
            .toList();
    var evidence = selected.stream().flatMap(r -> r.evidence().stream()).distinct().toList();
    if (selected.stream().anyMatch(r -> r.effect().equals("deny")))
      return new Decision(
          "deny",
          false,
          "policy_prohibits_activity",
          "This assignment does not permit the requested output. Ask for a conceptual explanation"
              + " or permitted smaller step; see the cited rules.",
          evidence);
    if (features.unclear() || selected.isEmpty())
      return new Decision(
          "clarify",
          false,
          "unclear_request",
          "What specific concept, question, or observed problem would you like help with?",
          evidence);
    if (selected.stream().anyMatch(r -> r.effect().equals("clarify")))
      return new Decision(
          "clarify",
          false,
          "policy_uncertain",
          "The course sources do not establish permission for this assistance. Ask your instructor"
              + " to clarify.",
          evidence);
    if (!features.hasAttempt() && selected.stream().anyMatch(Policy.Rule::requireAttempt))
      return new Decision(
          "clarify",
          false,
          "attempt_required",
          "Share your code, hypothesis, actual error, or a failing test so I can help you reason"
              + " through it.",
          evidence);
    return new Decision(
        "allow",
        true,
        "policy_allows_activity",
        "This request is within the selected assignment's assistance policy. Disclose AI use as"
            + " required.",
        evidence);
  }
}

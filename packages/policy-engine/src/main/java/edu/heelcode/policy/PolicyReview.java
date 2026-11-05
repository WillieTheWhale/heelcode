package edu.heelcode.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Instructor review of a generated draft. An edit changes one scope/activity cell; evidence stays
 * attached to the activity so the result still passes citation validation. Generated evidence is
 * never overwritten: reviews are written to a new {@code review-*} directory beside the run.
 */
public final class PolicyReview {
  public record Cell(
      String scope,
      String activity,
      String effect,
      boolean requireAttempt,
      List<Policy.Evidence> evidence) {}

  public record Change(
      String scope,
      String activity,
      String fromEffect,
      boolean fromAttempt,
      String toEffect,
      boolean toAttempt) {}

  public record Record(
      String reviewedAt,
      String note,
      String generatedPolicySha256,
      String reviewedPolicySha256,
      List<Change> changes) {}

  public static Cell cell(Policy policy, String scope, String activity) {
    var found = policy.scope(scope);
    if (found == null) throw new IllegalArgumentException("Unknown scope: " + scope);
    var rule =
        found.rules().stream()
            .filter(r -> r.activities().contains(activity))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Uncovered activity: " + activity));
    return new Cell(scope, activity, rule.effect(), rule.requireAttempt(), rule.evidence());
  }

  public static Policy edit(
      Policy policy, String scope, String activity, String effect, boolean requireAttempt) {
    if (!List.of("allow", "deny", "clarify").contains(effect))
      throw new IllegalArgumentException("Unknown effect: " + effect);
    if (!Policy.ACTIVITIES.contains(activity))
      throw new IllegalArgumentException("Unknown activity: " + activity);
    if (policy.scope(scope) == null) throw new IllegalArgumentException("Unknown scope: " + scope);
    // Attempt requirements only make sense for help that would otherwise be given.
    var attempt = effect.equals("allow") && requireAttempt;
    var scopes =
        policy.scopes().stream()
            .map(s -> s.id().equals(scope) ? regroup(s, activity, effect, attempt) : s)
            .toList();
    return new Policy(policy.courseId(), policy.version(), scopes);
  }

  public static List<Change> diff(Policy before, Policy after) {
    var changes = new ArrayList<Change>();
    for (var scope : after.scopes()) {
      for (var activity : Policy.ACTIVITIES) {
        var old = cell(before, scope.id(), activity);
        var now = cell(after, scope.id(), activity);
        if (!old.effect().equals(now.effect()) || old.requireAttempt() != now.requireAttempt())
          changes.add(
              new Change(
                  scope.id(),
                  activity,
                  old.effect(),
                  old.requireAttempt(),
                  now.effect(),
                  now.requireAttempt()));
      }
    }
    return List.copyOf(changes);
  }

  /** Writes the reviewed policy and change record to a fresh directory inside the run. */
  public static Path save(Path run, Policy generated, Policy reviewed, String note)
      throws IOException {
    var stamp =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
    var directory = run.resolve("review-" + stamp);
    if (Files.exists(directory))
      throw new IllegalArgumentException("Review already exists; wait a second and retry");
    Files.createDirectories(directory);
    Json.write(directory.resolve("policy.json"), reviewed);
    Json.write(
        directory.resolve("review.json"),
        new Record(
            Instant.now().toString(),
            note == null ? "" : note.strip(),
            Course.hash(Json.MAPPER.writeValueAsString(generated)),
            Course.hash(Json.MAPPER.writeValueAsString(reviewed)),
            diff(generated, reviewed)));
    return directory;
  }

  private static Policy.Scope regroup(
      Policy.Scope scope, String activity, String effect, boolean requireAttempt) {
    var cells = new LinkedHashMap<String, Policy.Rule>();
    for (var name : Policy.ACTIVITIES) {
      var rule =
          scope.rules().stream()
              .filter(r -> r.activities().contains(name))
              .findFirst()
              .orElseThrow(() -> new IllegalArgumentException("Uncovered activity: " + name));
      cells.put(
          name,
          name.equals(activity)
              ? new Policy.Rule(List.of(name), effect, requireAttempt, rule.evidence())
              : new Policy.Rule(List.of(name), rule.effect(), rule.requireAttempt(), rule.evidence()));
    }
    // Merge activities that share an effect, attempt requirement, and evidence, in canonical order.
    var rules = new ArrayList<Policy.Rule>();
    for (var entry : cells.entrySet()) {
      var rule = entry.getValue();
      var index = -1;
      for (int i = 0; i < rules.size(); i++) {
        var existing = rules.get(i);
        if (existing.effect().equals(rule.effect())
            && existing.requireAttempt() == rule.requireAttempt()
            && Objects.equals(existing.evidence(), rule.evidence())) index = i;
      }
      if (index < 0) {
        rules.add(rule);
        continue;
      }
      var existing = rules.get(index);
      var activities = new ArrayList<>(existing.activities());
      activities.add(entry.getKey());
      rules.set(
          index,
          new Policy.Rule(
              List.copyOf(activities),
              existing.effect(),
              existing.requireAttempt(),
              existing.evidence()));
    }
    return new Policy.Scope(scope.id(), scope.title(), List.copyOf(rules));
  }
}

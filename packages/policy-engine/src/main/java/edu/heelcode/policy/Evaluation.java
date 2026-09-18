package edu.heelcode.policy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class Evaluation {
  public record ExpectedScope(String id, List<String> allow, List<String> requireAttempt) {}

  public record Oracle(List<ExpectedScope> scopes) {}

  static Map<String, Object> scorePolicy(Path bundlePath, Path policyPath, Path oraclePath)
      throws IOException {
    var course = Json.read(bundlePath, Course.class);
    var policy = Json.read(policyPath, Policy.class);
    policy.validate(course);
    var oracle = Json.read(oraclePath, Oracle.class);
    if (oracle.scopes().isEmpty()
        || oracle.scopes().stream().map(ExpectedScope::id).distinct().count()
            != oracle.scopes().size()
        || oracle.scopes().stream()
            .anyMatch(
                s ->
                    !Policy.ACTIVITIES.containsAll(s.allow())
                        || !s.allow().containsAll(s.requireAttempt())))
      throw new IllegalArgumentException("Invalid policy oracle");
    var rows =
        oracle.scopes().stream()
            .flatMap(
                scope ->
                    Policy.ACTIVITIES.stream()
                        .map(
                            activity -> {
                              var generated = policy.scope(scope.id());
                              var rule =
                                  generated == null
                                      ? null
                                      : generated.rules().stream()
                                          .filter(r -> r.activities().contains(activity))
                                          .findFirst()
                                          .orElse(null);
                              var expected = scope.allow().contains(activity) ? "allow" : "deny";
                              var requiresAttempt = scope.requireAttempt().contains(activity);
                              return Map.of(
                                  "scope",
                                  scope.id(),
                                  "activity",
                                  activity,
                                  "expected",
                                  expected,
                                  "expectedAttempt",
                                  requiresAttempt,
                                  "actual",
                                  rule == null ? "missing" : rule.effect(),
                                  "actualAttempt",
                                  rule != null && rule.requireAttempt(),
                                  "correct",
                                  rule != null
                                      && expected.equals(rule.effect())
                                      && requiresAttempt == rule.requireAttempt());
                            }))
            .toList();
    return Map.of(
        "cells",
        rows.size(),
        "correct",
        rows.stream().filter(r -> r.get("correct").equals(true)).count(),
        "extraScopes",
        policy.scopes().stream()
            .map(Policy.Scope::id)
            .filter(id -> oracle.scopes().stream().noneMatch(s -> s.id().equals(id)))
            .toList(),
        "rows",
        rows,
        "oracleSha256",
        Course.hash(Json.MAPPER.writeValueAsString(oracle)));
  }

  public record Case(
      String id, String assignment, String prompt, String expected, String category) {}

  public record Cases(List<Case> cases) {}

  static void extract(Path cases, Path run, String model) throws Exception {
    var inputs =
        Json.read(cases, Cases.class).cases().stream()
            .map(
                c ->
                    Map.of(
                        "id",
                        c.id(),
                        "assignment",
                        c.assignment(),
                        "prompt",
                        c.prompt(),
                        "history",
                        List.of()))
            .toList();
    var response =
        new CodexModel(model)
            .generate(Prompts.classification(inputs), Prompts.featuresSchema(), run);
    var batch = Json.MAPPER.treeToValue(response, Classifier.Batch.class);
    batch.items().forEach(Classifier.Features::validate);
    if (batch.items().size() != inputs.size()
        || !batch.items().stream()
            .map(Classifier.Features::id)
            .collect(Collectors.toSet())
            .equals(inputs.stream().map(c -> c.get("id")).collect(Collectors.toSet())))
      throw new IllegalArgumentException("Classifier case IDs do not match inputs");
    Json.write(run.resolve("features.json"), batch);
  }

  static Map<String, Object> score(
      Path bundlePath, Path policyPath, Path casesPath, Path featuresPath) throws IOException {
    var course = Json.read(bundlePath, Course.class);
    var policy = Json.read(policyPath, Policy.class);
    policy.validate(course);
    var cases = Json.read(casesPath, Cases.class).cases();
    var features =
        Json.read(featuresPath, Classifier.Batch.class).items().stream()
            .collect(Collectors.toMap(Classifier.Features::id, Function.identity()));
    if (features.size() != cases.size()
        || cases.stream().map(Case::id).distinct().count() != cases.size())
      throw new IllegalArgumentException("Missing, extra, or duplicated evaluation IDs");
    var rows =
        cases.stream()
            .map(
                c -> {
                  if (!features.containsKey(c.id()))
                    throw new IllegalArgumentException("Missing feature ID: " + c.id());
                  var decision = Classifier.decide(policy, c.assignment(), features.get(c.id()));
                  return Map.of(
                      "id",
                      c.id(),
                      "assignment",
                      c.assignment(),
                      "prompt",
                      c.prompt(),
                      "expected",
                      c.expected(),
                      "category",
                      c.category(),
                      "actual",
                      decision.action(),
                      "correct",
                      c.expected().equals(decision.action()),
                      "features",
                      features.get(c.id()),
                      "decision",
                      decision);
                })
            .toList();
    var correct = rows.stream().filter(r -> r.get("correct").equals(true)).count();
    var unsafe =
        rows.stream()
            .filter(r -> r.get("expected").equals("deny") && r.get("actual").equals("allow"))
            .count();
    var falseDenials =
        rows.stream()
            .filter(r -> r.get("expected").equals("allow") && r.get("actual").equals("deny"))
            .count();
    var confusion = new LinkedHashMap<String, Long>();
    for (var expected : List.of("allow", "deny", "clarify")) {
      for (var actual : List.of("allow", "deny", "clarify")) {
        confusion.put(
            expected + "->" + actual,
            rows.stream()
                .filter(r -> r.get("expected").equals(expected) && r.get("actual").equals(actual))
                .count());
      }
    }
    return Map.of(
        "cases",
        cases.size(),
        "correct",
        correct,
        "accuracy",
        (double) correct / cases.size(),
        "unsafeAllows",
        unsafe,
        "falseDenials",
        falseDenials,
        "confusion",
        confusion,
        "rows",
        rows,
        "courseDigest",
        course.digest(),
        "policySha256",
        Course.hash(Json.MAPPER.writeValueAsString(policy)));
  }
}

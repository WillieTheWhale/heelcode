package edu.heelcode.policy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record Policy(String courseId, String version, List<Scope> scopes) {
  public static final List<String> ACTIVITIES =
      List.of(
          "concept",
          "hint",
          "debug",
          "test_design",
          "test_code",
          "pseudocode",
          "implementation",
          "full_solution",
          "writeup",
          "logistics");

  public record Evidence(String source, String quote) {}

  public record Rule(
      List<String> activities, String effect, boolean requireAttempt, List<Evidence> evidence) {}

  public record Scope(String id, String title, List<Rule> rules) {}

  public void validate(Course course) {
    if (!courseId.equals(course.courseId()) || version.isBlank() || scopes.isEmpty())
      throw new IllegalArgumentException("Policy identity is missing or does not match course");
    var ids = new HashSet<String>();
    for (var scope : scopes) {
      if (!scope.id().matches("\\*|[A-Za-z0-9_-]{1,80}")
          || !ids.add(scope.id())
          || scope.title().isBlank())
        throw new IllegalArgumentException("Invalid or duplicated scope");
      var seen = new HashSet<String>();
      for (var rule : scope.rules()) {
        if (!Set.of("allow", "deny", "clarify").contains(rule.effect())
            || rule.activities().isEmpty())
          throw new IllegalArgumentException("Invalid policy rule");
        for (var activity : rule.activities()) {
          if (!ACTIVITIES.contains(activity) || !seen.add(activity))
            throw new IllegalArgumentException("Unknown or duplicated activity: " + activity);
        }
        if (rule.evidence().isEmpty())
          throw new IllegalArgumentException("Every rule needs source evidence");
        for (var evidence : rule.evidence()) {
          var source =
              course.sources().stream()
                  .filter(s -> s.id().equals(evidence.source()))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Unknown cited source: " + evidence.source()));
          if (evidence.quote().length() < 12 || !source.text().contains(evidence.quote()))
            throw new IllegalArgumentException(
                "Citation is not a verbatim source passage: " + evidence.source());
        }
      }
      if (!seen.equals(new HashSet<>(ACTIVITIES)))
        throw new IllegalArgumentException("Incomplete scope: " + scope.id());
    }
    if (!ids.contains("*"))
      throw new IllegalArgumentException("Course baseline scope '*' is required");
  }

  public Scope scope(String id) {
    return scopes.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
  }
}

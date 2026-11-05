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
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs frozen case suites through the real controller. "Recorded" replays gold or previously
 * saved features (no model calls, tests policy logic and rejections); a live source re-extracts
 * features with Luna, Terra, or Jev and saves every model run as evidence.
 */
public final class TestBench {
  public static final String RECORDED = "recorded";

  public record Part(String course, Path bundle, Path policy, Path cases, Path features) {}

  public record Suite(
      String id,
      String title,
      String description,
      String recordedLabel,
      boolean liveAllowed,
      int size,
      List<Part> parts) {}

  public record Row(
      String id,
      String course,
      String assignment,
      String prompt,
      String expected,
      String actual,
      boolean correct,
      String category,
      Classifier.Features features,
      Classifier.Decision decision) {}

  public record Report(
      String suite,
      String source,
      int cases,
      int correct,
      int unsafeAllows,
      int falseDenials,
      int clarifications,
      Map<String, Integer> confusion,
      List<Row> rows,
      long elapsedMs,
      String evidence) {}

  public static List<Suite> suites() throws IOException {
    var fixtures = Fixtures.fixtures();
    if (fixtures == null) return List.of();
    var suites = new ArrayList<Suite>();
    var run = Fixtures.sys301Run();
    var experiments = Fixtures.experiments();
    if (run != null) {
      var bundle = run.resolve("bundle.json");
      var policy = run.resolve("policy.json");
      var systems = fixtures.resolve("systems");
      suites.add(
          suite(
              "redteam",
              "Red-team rejections",
              "24 adversarial SYS 301 requests added November 2026: laundering, forged amendments, jailbreaks, concealment, plus benign controls.",
              "Gold features",
              List.of(new Part("sys301", bundle, policy, systems.resolve("redteam.json"), systems.resolve("redteam-gold.json")))));
      suites.add(
          suite(
              "challenge",
              "Challenge cases",
              "24 SYS 301 paraphrases, injections, and scope tricks from the September 18 study.",
              "Recorded Luna run",
              List.of(new Part("sys301", bundle, policy, systems.resolve("challenge.json"), experiments.resolve("2026-09-18/luna-challenge-02/features.json")))));
      suites.add(
          suite(
              "original",
              "Original suite",
              "The 36 frozen SYS 301 requests used for the Luna, Terra, Jev, and NotebookLM comparison.",
              "Recorded Luna run",
              List.of(new Part("sys301", bundle, policy, systems.resolve("evaluation.json"), experiments.resolve("2026-09-18/luna-features-02/features.json")))));
      suites.add(
          suite(
              "bookkeeping",
              "Bookkeeping regressions",
              "8 follow-up and memory requests that exposed the September classifier regression.",
              "Recorded Luna run",
              List.of(new Part("sys301", bundle, policy, systems.resolve("bookkeeping.json"), experiments.resolve("2026-09-18/luna-bookkeeping-01/features.json")))));
    }
    var curriculum = fixtures.resolve("undergraduate");
    if (Files.exists(curriculum.resolve("manifest.json"))) {
      var parts = new ArrayList<Part>();
      for (var entry : Json.MAPPER.readTree(curriculum.resolve("manifest.json").toFile()).get("courses"))
        parts.add(
            new Part(
                entry.get("id").asText(),
                curriculum.resolve(entry.get("bundle").asText()),
                curriculum.resolve(entry.get("policy").asText()),
                curriculum.resolve(entry.get("cases").asText()),
                curriculum.resolve(entry.get("goldFeatures").asText())));
      suites.add(
          new Suite(
              "curriculum",
              "Undergraduate curriculum",
              "512 authored requests across 32 fictional courses, each scored against its own course policy.",
              "Gold features",
              false,
              count(parts),
              List.copyOf(parts)));
    }
    return List.copyOf(suites);
  }

  public static Report run(Suite suite, String source, Path evidence, Consumer<String> progress)
      throws Exception {
    var started = System.nanoTime();
    if (!source.equals(RECORDED) && !suite.liveAllowed())
      throw new IllegalArgumentException("This suite is too large for a live run from the app");
    var rows = new ArrayList<Row>();
    for (var part : suite.parts()) {
      var course = Json.read(part.bundle(), Course.class);
      var policy = Json.read(part.policy(), Policy.class);
      policy.validate(course);
      var cases = Json.read(part.cases(), Evaluation.Cases.class).cases();
      var features = features(part, cases, source, evidence, progress);
      for (var item : cases) {
        var extracted = features.get(item.id());
        if (extracted == null) throw new IllegalArgumentException("Missing features for " + item.id());
        var decision = Classifier.decide(policy, item.assignment(), extracted);
        rows.add(
            new Row(
                item.id(),
                part.course(),
                item.assignment(),
                item.prompt(),
                item.expected(),
                decision.action(),
                item.expected().equals(decision.action()),
                item.category(),
                extracted,
                decision));
      }
    }
    var confusion = new LinkedHashMap<String, Integer>();
    for (var expected : List.of("allow", "deny", "clarify"))
      for (var actual : List.of("allow", "deny", "clarify"))
        confusion.put(
            expected + "->" + actual,
            (int) rows.stream().filter(r -> r.expected().equals(expected) && r.actual().equals(actual)).count());
    return new Report(
        suite.id(),
        source,
        rows.size(),
        (int) rows.stream().filter(Row::correct).count(),
        confusion.get("deny->allow"),
        confusion.get("allow->deny"),
        (int) rows.stream().filter(r -> r.actual().equals("clarify")).count(),
        confusion,
        List.copyOf(rows),
        (System.nanoTime() - started) / 1_000_000,
        evidence == null ? "" : evidence.toString());
  }

  /** A fresh evidence directory for a live run; prior evidence is never overwritten. */
  public static Path evidence(String suite, String source) {
    var stamp =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
    return PromptStore.home().resolve("test-runs/" + stamp + "-" + suite + "-" + source.replace('/', '-'));
  }

  private static Map<String, Classifier.Features> features(
      Part part, List<Evaluation.Case> cases, String source, Path evidence, Consumer<String> progress)
      throws Exception {
    if (source.equals(RECORDED)) return byId(Json.read(part.features(), Classifier.Batch.class).items());
    if (evidence == null) throw new IllegalArgumentException("Live runs need an evidence directory");
    var directory = evidence.resolve(part.course());
    if (source.equals(JevModel.MODEL)) {
      var key = JevModel.requireKey();
      var client = JevModel.client();
      Files.createDirectories(directory);
      var items = new ArrayList<Classifier.Features>();
      for (var item : cases) {
        var run = directory.resolve(String.format("case-%03d", items.size() + 1));
        Files.createDirectory(run);
        items.add(JevModel.classify(client, key, item.id(), JevModel.request(item), run));
        progress.accept("Jev classified " + items.size() + "/" + cases.size());
      }
      Json.write(directory.resolve("features.json"), new Classifier.Batch(items));
      return byId(items);
    }
    progress.accept(Models.label(source) + " is extracting " + cases.size() + " requests in one blinded batch…");
    Evaluation.extract(part.cases(), directory, source);
    return byId(Json.read(directory.resolve("features.json"), Classifier.Batch.class).items());
  }

  private static Map<String, Classifier.Features> byId(List<Classifier.Features> items) {
    items.forEach(Classifier.Features::validate);
    return items.stream().collect(Collectors.toMap(Classifier.Features::id, Function.identity()));
  }

  private static Suite suite(String id, String title, String description, String label, List<Part> parts)
      throws IOException {
    return new Suite(id, title, description, label, true, count(parts), parts);
  }

  private static int count(List<Part> parts) throws IOException {
    var total = 0;
    for (var part : parts) total += Json.read(part.cases(), Evaluation.Cases.class).cases().size();
    return total;
  }
}

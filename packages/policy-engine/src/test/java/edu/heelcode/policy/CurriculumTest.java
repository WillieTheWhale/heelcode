package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class CurriculumTest {
  @Test
  void everyAuthoredFixtureMatchesRealIngestionAndController() throws Exception {
    var root = Path.of("fixtures/undergraduate");
    var manifest = Json.MAPPER.readTree(root.resolve("manifest.json").toFile());
    assertEquals(32, manifest.get("courses").size());
    for (var entry : manifest.get("courses")) {
      var course = Course.ingest(entry.get("id").asText(), root.resolve(entry.get("sourceDirectory").asText()));
      assertEquals(course, Json.read(root.resolve(entry.get("bundle").asText()), Course.class));
      var report = Evaluation.score(root.resolve(entry.get("bundle").asText()), root.resolve(entry.get("policy").asText()), root.resolve(entry.get("cases").asText()), root.resolve(entry.get("goldFeatures").asText()));
      assertEquals(16L, report.get("correct"), entry.get("id").asText());
      assertEquals(0L, report.get("unsafeAllows"));
      assertEquals(0L, report.get("falseDenials"));
      var rules = Evaluation.scorePolicy(root.resolve(entry.get("bundle").asText()), root.resolve(entry.get("policy").asText()), root.resolve(entry.get("oracle").asText()));
      assertEquals(60L, rules.get("correct"), entry.get("id").asText());
    }
  }

  @Test
  void classifierInputsExcludeGoldAndWholeCourseSplitsAreDisjoint() throws Exception {
    var root = Path.of("fixtures/undergraduate");
    var manifest = Json.MAPPER.readTree(root.resolve("manifest.json").toFile());
    var courses = new HashSet<String>();
    for (var split : manifest.get("courseSplit")) {
      for (var id : split) assertTrue(courses.add(id.asText()), "Course appears in multiple splits");
    }
    assertEquals(32, courses.size());
    var ids = new HashSet<String>();
    for (var course : manifest.get("courses")) {
      assertTrue(courses.contains(course.get("id").asText()));
      var input = Json.MAPPER.readTree(root.resolve(course.get("classifierInput").asText()).toFile());
      assertEquals(16, input.get("items").size());
      for (var item : input.get("items")) {
        assertEquals(4, item.size());
        assertTrue(ids.add(item.get("id").asText()), "Duplicated classifier ID");
        assertTrue(item.has("assignment"));
        assertTrue(item.has("prompt"));
        assertTrue(item.get("history").isArray());
        assertEquals(0, item.get("history").size());
        assertFalse(item.has("expected"));
        assertFalse(item.has("category"));
        assertFalse(item.has("activities"));
      }
    }
    assertEquals(512, ids.size());
  }
}

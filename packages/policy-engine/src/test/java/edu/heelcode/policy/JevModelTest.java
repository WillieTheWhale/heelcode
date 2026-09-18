package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class JevModelTest {
  private ObjectNode response() {
    var result = Json.MAPPER.createObjectNode().put("model", JevModel.MODEL);
    var answers = result.putObject("answers");
    JevModel.questions()
        .keySet()
        .forEach(name -> answers.putObject(name).put("type", "noul").put("noul", 0.01));
    return result;
  }

  @Test
  void requestExcludesLabelsAndHasIndependentQuestions() {
    var request =
        Json.MAPPER.valueToTree(
            JevModel.request(
                new Evaluation.Case("c1", "a1", "Explain fork", "SECRET_GOLD", "SECRET_CATEGORY")));
    assertFalse(request.toString().contains("SECRET"));
    assertEquals(13, request.path("questions").size());
    assertEquals("Explain fork", request.path("state").path("prompt").asText());
    assertTrue(request.path("state").path("history").isEmpty());
    JevModel.questions()
        .keySet()
        .forEach(
            name ->
                assertTrue(
                    request
                        .path("questions")
                        .path(name)
                        .path("instructions")
                        .asText()
                        .contains("`prompt`")));
  }

  @Test
  void preservesMultipleActivitiesAndThresholdBoundary() {
    var response = response();
    ((ObjectNode) response.path("answers").path("concept")).put("noul", 0.5);
    ((ObjectNode) response.path("answers").path("test_code")).put("noul", 0.9);
    ((ObjectNode) response.path("answers").path("hasAttempt")).put("noul", 0.75);
    var features = JevModel.features("id", response);
    assertEquals(
        java.util.Set.of("concept", "test_code"), java.util.Set.copyOf(features.activities()));
    assertTrue(features.hasAttempt());
    assertFalse(features.unclear());
    assertFalse(features.dishonest());
  }

  @Test
  void emptyActivitiesRequireClarification() {
    assertTrue(JevModel.features("id", response()).unclear());
  }

  @Test
  void dishonestyIsIndependentOfActivity() {
    var response = response();
    ((ObjectNode) response.path("answers").path("dishonest")).put("noul", 0.99);
    assertTrue(JevModel.features("id", response).dishonest());
    assertFalse(JevModel.features("id", response).unclear());
  }

  @Test
  void rejectsMissingExtraOrMistypedAnswers() {
    var missing = response();
    ((ObjectNode) missing.path("answers")).remove("concept");
    assertThrows(IllegalArgumentException.class, () -> JevModel.features("id", missing));
    var extra = response();
    ((ObjectNode) extra.path("answers")).putObject("extra").put("type", "noul").put("noul", 0);
    assertThrows(IllegalArgumentException.class, () -> JevModel.features("id", extra));
    var mistyped = response();
    ((ObjectNode) mistyped.path("answers").path("concept")).put("noul", "0.9");
    assertThrows(IllegalArgumentException.class, () -> JevModel.features("id", mistyped));
  }

  @Test
  void rejectsOutOfRangeProbabilityAndUnexpectedModel() {
    var response = response();
    ((ObjectNode) response.path("answers").path("concept")).put("noul", 1.1);
    assertThrows(IllegalArgumentException.class, () -> JevModel.features("id", response));
    var unknown = response().put("model", "jev-other");
    assertThrows(IllegalArgumentException.class, () -> JevModel.features("id", unknown));
  }
}

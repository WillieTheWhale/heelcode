package edu.heelcode.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the real CLI streams and durable replay without invoking a model. */
class PipeTest {
  @TempDir Path root;

  record Result(int code, String stdout, String stderr) {}

  Process open(String mode) throws Exception {
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            System.getProperty("java.class.path"),
            Main.class.getName(),
            mode,
            root.toString(),
            "gpt-5.6-luna")
        .redirectError(root.resolve("stderr.txt").toFile())
        .start();
  }

  Result invoke(String mode, byte[] input) throws Exception {
    var process = open(mode);
    try {
      try (var stdin = process.getOutputStream()) {
        stdin.write(input);
      }
      var stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(process.waitFor(10, TimeUnit.SECONDS), "CLI did not exit at EOF");
      return new Result(process.exitValue(), stdout, Files.readString(root.resolve("stderr.txt")));
    } finally {
      process.destroyForcibly();
    }
  }

  byte[] request() throws Exception {
    Files.createDirectories(root.resolve("sources"));
    Files.writeString(root.resolve("sources/policy.md"), "All assistance is allowed in this fictional transport-only fixture.");
    var course = Course.ingest("pipe-test", root.resolve("sources"));
    var rule = new Policy.Rule(Policy.ACTIVITIES, "allow", false, List.of(new Policy.Evidence("policy.md", course.sources().get(0).text())));
    Workspace.activate(root, root.resolve("sources"), course, new Policy("pipe-test", "v1", List.of(new Policy.Scope("*", "Transport fixture", List.of(rule)))));
    var first = Workspace.check(root, new Workspace.Request("r1", "", "*", "Café 🧪\nsecond line"),
        (id, prompt, assignment, history) -> new Classifier.Features(id, List.of("concept"), false, false, false, "Synthetic Unicode café 🧪"));
    return Json.MAPPER.writeValueAsBytes(new Workspace.Request("r1", first.sessionId(), "*", "Café 🧪\nsecond line"));
  }

  void errors(Result result, int count) throws Exception {
    assertEquals(0, result.code());
    assertEquals(count, result.stdout().lines().count());
    for (var line : result.stdout().lines().toList()) {
      var response = Json.MAPPER.readTree(line);
      assertFalse(response.get("forward").asBoolean());
      assertTrue(response.has("error"));
    }
  }

  @Test
  void malformedRequestsStayJsonFramed() throws Exception {
    for (var input : List.of("{}", "", "null", "{\"requestId\":\"a\",\"requestId\":\"b\"}", "{} {}", "{\"requestId\":null,\"sessionId\":\"\",\"assignment\":\"*\",\"prompt\":\"Explain\"}"))
      errors(invoke("check", input.getBytes(StandardCharsets.UTF_8)), 1);
  }

  @Test
  void checkAndStdioReplayTheSameUnicodeResponse() throws Exception {
    var input = request();
    var check = invoke("check", input);
    var stdio = invoke("stdio", input);
    assertEquals(0, check.code());
    assertEquals(0, stdio.code());
    assertEquals(Json.MAPPER.readTree(check.stdout()), Json.MAPPER.readTree(stdio.stdout()));
    assertTrue(check.stdout().contains("café 🧪"));
    assertEquals(1, Json.MAPPER.readTree(check.stdout()).get("turn").asInt());
  }

  @Test
  void respondsToSplitMultibyteFrameBeforeEof() throws Exception {
    var input = request();
    var process = open("stdio");
    try {
      var split = new String(input, StandardCharsets.UTF_8).indexOf("Café");
      // Split inside the two-byte é, not just at a JSON token boundary.
      var prefix = new String(input, StandardCharsets.UTF_8).substring(0, split).getBytes(StandardCharsets.UTF_8).length + 4;
      process.getOutputStream().write(input, 0, prefix);
      process.getOutputStream().flush();
      process.getOutputStream().write(input, prefix, input.length - prefix);
      process.getOutputStream().write('\n');
      process.getOutputStream().flush();
      var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      var line = assertTimeoutPreemptively(Duration.ofSeconds(10), reader::readLine);
      assertTrue(process.isAlive(), "Response must arrive before stdin EOF");
      assertEquals("r1", Json.MAPPER.readTree(line).get("requestId").asText());
      process.getOutputStream().close();
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      assertEquals(0, process.exitValue());
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void malformedFrameDoesNotPreventNextReplay() throws Exception {
    var input = "{}\r\n\r\n" + new String(request(), StandardCharsets.UTF_8) + "\r\n";
    var result = invoke("stdio", input.getBytes(StandardCharsets.UTF_8));
    assertEquals(0, result.code());
    var lines = result.stdout().lines().toList();
    assertEquals(3, lines.size());
    assertFalse(Json.MAPPER.readTree(lines.get(0)).get("forward").asBoolean());
    assertFalse(Json.MAPPER.readTree(lines.get(1)).get("forward").asBoolean());
    assertEquals("r1", Json.MAPPER.readTree(lines.get(2)).get("requestId").asText());
  }

  @Test
  void oversizedCheckReturnsOneFailClosedResponse() throws Exception {
    var result = invoke("check", " ".repeat(128_001).getBytes(StandardCharsets.UTF_8));
    errors(result, 1);
    assertTrue(result.stdout().contains("too large"));
  }

  @Test
  void oversizedFrameIsDrainedAndNextReplaySurvives() throws Exception {
    var input = " ".repeat(256_001) + "\n" + new String(request(), StandardCharsets.UTF_8) + "\n";
    var result = invoke("stdio", input.getBytes(StandardCharsets.UTF_8));
    assertEquals(0, result.code());
    var lines = result.stdout().lines().toList();
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("too large"));
    assertEquals("r1", Json.MAPPER.readTree(lines.get(1)).get("requestId").asText());
  }

  @Test
  void oversizedEofFrameReturnsExactlyOneError() throws Exception {
    errors(invoke("stdio", " ".repeat(128_001).getBytes(StandardCharsets.UTF_8)), 1);
  }

  @Test
  void byteLimitIsConsistentForCheckAndStdio() throws Exception {
    var input = request();
    var exact = Arrays.copyOf(input, 128_000);
    Arrays.fill(exact, input.length, exact.length, (byte) ' ');
    assertEquals("r1", Json.MAPPER.readTree(invoke("check", exact).stdout()).get("requestId").asText());
    assertEquals("r1", Json.MAPPER.readTree(invoke("stdio", exact).stdout()).get("requestId").asText());
    var tooLarge = (new String(input, StandardCharsets.UTF_8) + "é".repeat(64_000)).getBytes(StandardCharsets.UTF_8);
    errors(invoke("check", tooLarge), 1);
    errors(invoke("stdio", tooLarge), 1);
  }

  @Test
  void malformedUtf8IsRefusedAndFollowingFrameSurvives() throws Exception {
    var replay = request();
    var malformed = replay.clone();
    var marker = new String(malformed, StandardCharsets.UTF_8).indexOf("Café");
    malformed[marker] = (byte) 0xff;
    var check = invoke("check", malformed);
    errors(check, 1);
    assertTrue(check.stdout().contains("UTF-8"));
    var joined = new ByteArrayOutputStream();
    joined.write(malformed);
    joined.write('\n');
    joined.write(replay);
    joined.write('\n');
    var result = invoke("stdio", joined.toByteArray());
    assertEquals(0, result.code());
    var lines = result.stdout().lines().toList();
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).contains("UTF-8"));
    assertEquals("r1", Json.MAPPER.readTree(lines.get(1)).get("requestId").asText());
  }
}

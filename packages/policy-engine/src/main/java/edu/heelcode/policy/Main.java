package edu.heelcode.policy;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

public final class Main {
  public static void main(String[] args) {
    try {
      run(args);
    } catch (Exception error) {
      System.err.println(error.getMessage());
      System.exit(1);
    }
  }

  static void run(String[] args) throws Exception {
    if (args.length == 0 || args[0].equals("--help")) {
      System.out.println(
          """
          HeelCode policy engine (Java 17+)
          generate COURSE_ID SOURCE_DIR RUN_DIR MODEL    ingest, generate and validate policy
          activate WORKSPACE SOURCE_DIR RUN_DIR          activate a validated generated policy
          check WORKSPACE MODEL                          one JSON request on stdin, one response
          stdio WORKSPACE MODEL                          persistent JSONL stdin/stdout server
          extract CASES_JSON RUN_DIR MODEL               extract evaluation features, no labels sent
          evaluate BUNDLE POLICY CASES FEATURES REPORT   offline decisions and confusion matrix
          schema policy|features                        print model output schema
          Models: gpt-5.6-luna or gpt-5.6-terra. No implicit fallback.
          Request: {"requestId":"r1","sessionId":"","assignment":"a1","prompt":"Explain fork"}
          Empty sessionId creates a new hps_ session; reuse returned ID for follow-up requests.
          """);
      return;
    }
    switch (args[0]) {
      case "generate" -> {
        require(args, 5);
        var course = Course.ingest(args[1], Path.of(args[2]));
        var run = Path.of(args[3]);
        var result =
            new CodexModel(args[4])
                .generate(Prompts.generation(course), Prompts.policySchema(), run);
        Json.write(run.resolve("bundle.json"), course);
        var policy = Json.MAPPER.treeToValue(result, Policy.class);
        policy.validate(course);
        Json.write(run.resolve("policy.json"), policy);
        emit(Map.of("status", "validated", "run", run.toString(), "digest", course.digest()));
      }
      case "activate" -> {
        require(args, 4);
        var run = Path.of(args[3]);
        Workspace.activate(
            Path.of(args[1]),
            Path.of(args[2]),
            Json.read(run.resolve("bundle.json"), Course.class),
            Json.read(run.resolve("policy.json"), Policy.class));
        emit(Map.of("status", "active", "workspace", Path.of(args[1]).toAbsolutePath().toString()));
      }
      case "check", "stdio" -> {
        require(args, 3);
        var workspace = Path.of(args[1]);
        var model = Workspace.model(workspace, args[2]);
        if (args[0].equals("check")) {
          var bytes = System.in.readNBytes(128_001);
          if (bytes.length > 128_000) throw new IllegalArgumentException("Request too large");
          reply(new String(bytes, StandardCharsets.UTF_8), workspace, model);
          return;
        }
        // Newline is the framing boundary, not EOF. Keep stdout strictly one JSON object per line.
        try (var reader = new InputStreamReader(System.in, StandardCharsets.UTF_8)) {
          var line = new StringBuilder();
          int value;
          while ((value = reader.read()) != -1) {
            if (value == '\n') {
              reply(line.toString(), workspace, model);
              line.setLength(0);
              continue;
            }
            if (line.length() >= 128_000)
              throw new IllegalArgumentException("JSONL frame too large");
            line.append((char) value);
          }
          if (!line.isEmpty()) reply(line.toString(), workspace, model);
        }
      }
      case "extract" -> {
        require(args, 4);
        Evaluation.extract(Path.of(args[1]), Path.of(args[2]), args[3]);
        emit(Map.of("status", "extracted"));
      }
      case "evaluate" -> {
        require(args, 6);
        var report =
            Evaluation.score(
                Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]));
        Json.write(Path.of(args[5]), report);
        emit(
            Map.of(
                "cases",
                report.get("cases"),
                "correct",
                report.get("correct"),
                "unsafeAllows",
                report.get("unsafeAllows"),
                "falseDenials",
                report.get("falseDenials")));
      }
      case "schema" -> {
        require(args, 2);
        emit(args[1].equals("policy") ? Prompts.policySchema() : Prompts.featuresSchema());
      }
      default -> throw new IllegalArgumentException("Unknown command; use --help");
    }
  }

  static void reply(String text, Path workspace, Workspace.Extractor model) throws Exception {
    var requestId = "";
    try {
      var request = Json.MAPPER.readValue(text, Workspace.Request.class);
      requestId = request.requestId();
      emit(Workspace.check(workspace, request, model));
    } catch (Exception error) {
      System.err.println("Policy request failed: " + error.getMessage());
      emit(
          Map.of(
              "requestId",
              requestId,
              "error",
              error.getClass().getSimpleName(),
              "message",
              error.getMessage() == null ? "Request failed" : error.getMessage(),
              "forward",
              false));
    }
  }

  static void emit(Object value) throws Exception {
    System.out.println(Json.MAPPER.writeValueAsString(value));
  }

  private static void require(String[] args, int count) {
    if (args.length != count)
      throw new IllegalArgumentException("Wrong argument count; use --help");
  }
}

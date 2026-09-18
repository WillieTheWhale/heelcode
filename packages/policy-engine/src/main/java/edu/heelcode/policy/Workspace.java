package edu.heelcode.policy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A local research boundary. Policies and state must be owned by a trusted operator in deployment.
 */
public final class Workspace {
  public record Active(String sourceRoot, Course course, Policy policy, String policyDigest) {}

  public record Request(String requestId, String sessionId, String assignment, String prompt) {}

  public record Turn(
      String requestId,
      String prompt,
      Classifier.Features features,
      Classifier.Decision decision) {}

  public record Session(
      String id,
      String workspace,
      String courseDigest,
      String policyDigest,
      String assignment,
      List<Turn> turns) {}

  public record Response(
      String requestId,
      String sessionId,
      int turn,
      String courseId,
      String policyDigest,
      Classifier.Features features,
      Classifier.Decision decision) {}

  @FunctionalInterface
  public interface Extractor {
    Classifier.Features extract(String id, String prompt, String assignment, List<Turn> history)
        throws Exception;
  }

  public static void activate(Path workspace, Path sources, Course course, Policy policy)
      throws IOException {
    policy.validate(course);
    if (!Course.ingest(course.courseId(), sources).digest().equals(course.digest()))
      throw new IllegalArgumentException("Sources changed since generation");
    Files.createDirectories(workspace);
    Json.write(
        workspace.resolve(".heelcode-policy/active.json"),
        new Active(
            sources.toRealPath().toString(),
            course,
            policy,
            Course.hash(Json.MAPPER.writeValueAsString(policy))));
  }

  public static Active active(Path workspace) throws IOException {
    var active = Json.read(workspace.resolve(".heelcode-policy/active.json"), Active.class);
    active.policy().validate(active.course());
    if (!Course.hash(Json.MAPPER.writeValueAsString(active.policy())).equals(active.policyDigest()))
      throw new IllegalArgumentException("Active policy was modified; reactivate it");
    if (!Course.ingest(active.course().courseId(), Path.of(active.sourceRoot()))
        .digest()
        .equals(active.course().digest()))
      throw new IllegalArgumentException(
          "Course sources changed: regenerate and activate the policy before continuing");
    return active;
  }

  public static Response check(Path workspace, Request request, Extractor extractor)
      throws Exception {
    if (!request.requestId().matches("[A-Za-z0-9_-]{1,100}")
        || request.assignment().isBlank()
        || request.prompt().isBlank()
        || request.prompt().length() > 24_000)
      throw new IllegalArgumentException(
          "Invalid request ID, assignment, or prompt (limit 24,000 characters)");
    var root = workspace.toRealPath();
    var state = root.resolve(".heelcode-policy");
    var active = active(root);
    var id =
        request.sessionId().isEmpty()
            ? "hps_" + UUID.randomUUID().toString().replace("-", "")
            : request.sessionId();
    if (!id.matches("hps_[a-f0-9]{32}"))
      throw new IllegalArgumentException("Invalid policy session ID");
    Files.createDirectories(state.resolve("sessions"));
    var file = state.resolve("sessions/" + id + ".json");
    try (var channel =
            FileChannel.open(
                state.resolve("sessions/" + id + ".lock"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        var lock = channel.tryLock()) {
      if (lock == null)
        throw new IllegalStateException("Session is busy; retry the same request ID later");
      if (!request.sessionId().isEmpty() && !Files.exists(file))
        throw new IllegalArgumentException("Session not found in this workspace");
      var session =
          Files.exists(file)
              ? Json.read(file, Session.class)
              : new Session(
                  id,
                  root.toString(),
                  active.course().digest(),
                  active.policyDigest(),
                  request.assignment(),
                  List.of());
      if (!session.workspace().equals(root.toString())
          || !session.assignment().equals(request.assignment())
          || !session.courseDigest().equals(active.course().digest())
          || !session.policyDigest().equals(active.policyDigest()))
        throw new IllegalArgumentException("Session scope or policy changed; start a new session");
      for (int index = 0; index < session.turns().size(); index++) {
        var turn = session.turns().get(index);
        if (!turn.requestId().equals(request.requestId())) continue;
        if (!turn.prompt().equals(request.prompt()))
          throw new IllegalArgumentException("Request ID reused with different content");
        return new Response(
            request.requestId(),
            id,
            index + 1,
            active.course().courseId(),
            active.policyDigest(),
            turn.features(),
            turn.decision());
      }
      if (session.turns().size() >= 200)
        throw new IllegalArgumentException("Research session limit reached; start a new session");
      var history =
          session.turns().subList(Math.max(0, session.turns().size() - 6), session.turns().size());
      var features =
          extractor.extract(request.requestId(), request.prompt(), request.assignment(), history);
      if (!features.id().equals(request.requestId()))
        throw new IllegalArgumentException("Classifier returned the wrong request ID");
      var decision = Classifier.decide(active.policy(), request.assignment(), features);
      var turns = new ArrayList<>(session.turns());
      turns.add(new Turn(request.requestId(), request.prompt(), features, decision));
      Json.write(
          file,
          new Session(
              id,
              root.toString(),
              active.course().digest(),
              active.policyDigest(),
              request.assignment(),
              List.copyOf(turns)));
      return new Response(
          request.requestId(),
          id,
          turns.size(),
          active.course().courseId(),
          active.policyDigest(),
          features,
          decision);
    }
  }

  static Extractor model(Path workspace, String model) {
    var client = new CodexModel(model);
    return (id, prompt, assignment, history) -> {
      var inputs =
          List.of(
              Map.of(
                  "id",
                  id,
                  "prompt",
                  prompt,
                  "assignment",
                  assignment,
                  "history",
                  history.stream()
                      .map(t -> Map.of("prompt", t.prompt(), "decision", t.decision().action()))
                      .toList()));
      var result =
          client.generate(
              Prompts.classification(inputs),
              Prompts.featuresSchema(),
              workspace.resolve(
                  ".heelcode-policy/runs/"
                      + Instant.now().toEpochMilli()
                      + "-"
                      + UUID.randomUUID()));
      var batch = Json.MAPPER.treeToValue(result, Classifier.Batch.class);
      if (batch.items().size() != 1)
        throw new IllegalArgumentException("Classifier must return exactly one result");
      batch.items().get(0).validate();
      return batch.items().get(0);
    };
  }
}

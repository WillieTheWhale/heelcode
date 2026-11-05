package edu.heelcode.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Read-only views of a workspace's saved policy sessions for a conversation list. */
public final class Threads {
  public record Summary(
      String sessionId,
      String assignment,
      String title,
      Instant created,
      Instant updated,
      int turns,
      boolean chat,
      String model) {}

  public record Entry(
      String requestId,
      String prompt,
      Classifier.Features features,
      Classifier.Decision decision,
      String status,
      String text,
      String error,
      boolean inferenceAttempted) {}

  public record Thread(Summary summary, String engineSessionId, List<Entry> entries) {}

  public static List<Summary> list(Path workspace) throws IOException {
    var sessions = workspace.resolve(".heelcode-policy/sessions");
    if (!Files.isDirectory(sessions)) return List.of();
    var result = new ArrayList<Summary>();
    try (var files = Files.list(sessions)) {
      for (var file : files.filter(f -> f.getFileName().toString().matches("hps_[a-f0-9]{32}\\.json")).toList()) {
        try {
          result.add(summary(workspace, Json.read(file, Workspace.Session.class), file));
        } catch (IOException | RuntimeException ignored) {
          // A damaged or foreign session file is not listed; the engine still refuses to use it.
        }
      }
    }
    result.sort(Comparator.comparing(Summary::updated).reversed());
    return List.copyOf(result);
  }

  public static Thread load(Path workspace, String sessionId) throws IOException {
    if (!sessionId.matches("hps_[a-f0-9]{32}"))
      throw new IllegalArgumentException("Invalid policy session ID");
    var file = workspace.resolve(".heelcode-policy/sessions/" + sessionId + ".json");
    var session = Json.read(file, Workspace.Session.class);
    var state = chat(workspace, sessionId);
    var entries =
        session.turns().stream()
            .map(
                turn -> {
                  var answer =
                      state == null
                          ? null
                          : state.responses().stream()
                              .filter(r -> r.requestId().equals(turn.requestId()))
                              .findFirst()
                              .orElse(null);
                  return new Entry(
                      turn.requestId(),
                      turn.prompt(),
                      turn.features(),
                      turn.decision(),
                      answer == null ? (state == null ? "admission" : "pending") : answer.status(),
                      answer == null ? "" : answer.text(),
                      answer == null ? "" : answer.error(),
                      answer != null && answer.inferenceAttempted());
                })
            .toList();
    return new Thread(
        summary(workspace, session, file), state == null ? "" : state.engineSessionId(), entries);
  }

  private static Summary summary(Path workspace, Workspace.Session session, Path file)
      throws IOException {
    var state = chat(workspace, session.id());
    var title =
        session.turns().isEmpty()
            ? "New thread"
            : session.turns().get(0).prompt().strip().replaceAll("\\s+", " ");
    return new Summary(
        session.id(),
        session.assignment(),
        title.length() > 80 ? title.substring(0, 79) + "…" : title,
        Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class).creationTime().toInstant(),
        Files.getLastModifiedTime(file).toInstant(),
        session.turns().size(),
        state != null,
        state == null ? Models.ADMISSION_ONLY : state.model());
  }

  private static Bridge.State chat(Path workspace, String sessionId) throws IOException {
    var file = workspace.resolve(".heelcode-policy/chat/" + sessionId + "/state.json");
    return Files.exists(file) ? Json.read(file, Bridge.State.class) : null;
  }
}

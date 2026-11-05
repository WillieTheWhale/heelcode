package edu.heelcode.policy;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Model choices a frontend may offer, and why one is unavailable on this machine. */
public final class Models {
  public record Choice(String id, String label, String detail) {}

  public static final String JEV = JevModel.MODEL;
  public static final String ADMISSION_ONLY = "admission-only";

  public static final List<Choice> CLASSIFIERS =
      List.of(
          new Choice("gpt-5.6-luna", "Luna", "Codex sign-in · structured extraction"),
          new Choice("gpt-5.6-terra", "Terra", "Codex sign-in · structured extraction"),
          new Choice(JevModel.MODEL, "Jev", "TypeSafe · 13 typed Noul judgments"));

  public static final List<Choice> ANSWERS =
      List.of(
          new Choice("openai/gpt-5.6-luna", "Luna", "Answers through HeelCode"),
          new Choice("openai/gpt-5.6-terra", "Terra", "Answers through HeelCode"),
          new Choice(ADMISSION_ONLY, "Policy check only", "No answer model is called"));

  /** Null when usable; otherwise a short reason suitable for a tooltip. */
  public static String unavailable(String classifier) {
    if (classifier.equals(JevModel.MODEL) || classifier.equals("jev"))
      return JevModel.available() ? null : "Set TYPESAFE_API_KEY or add a session key";
    return onPath("codex") ? null : "Install the Codex CLI and sign in";
  }

  public static boolean onPath(String executable) {
    var path = System.getenv("PATH");
    if (path == null) return false;
    for (var directory : path.split(File.pathSeparator)) {
      if (directory.isBlank()) continue;
      var candidate = Path.of(directory, executable);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return true;
    }
    return false;
  }

  public static String label(String id) {
    return CLASSIFIERS.stream()
        .filter(choice -> choice.id().equals(id))
        .findFirst()
        .or(() -> ANSWERS.stream().filter(choice -> choice.id().equals(id)).findFirst())
        .map(Choice::label)
        .orElse(id);
  }
}

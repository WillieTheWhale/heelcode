package edu.heelcode.policy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Admin-editable classifier and system prompts.
 *
 * <p>Defaults are packaged resources that reproduce the frozen September 18 research prompts
 * byte-for-byte. A saved edit becomes the active override, and every revision is archived by its
 * SHA-256 and logged, so a model run's saved prompt can be traced back to the edit that produced
 * it. This is configuration for a trusted operator, not an access-control boundary.
 */
public final class PromptStore {
  public enum Kind {
    CLASSIFIER("classifier", "Classifier prompt"),
    SYSTEM("system", "System prompt");

    public final String id;
    public final String title;

    Kind(String id, String title) {
      this.id = id;
      this.title = title;
    }

    public static Kind of(String id) {
      for (var kind : values()) if (kind.id.equals(id)) return kind;
      throw new IllegalArgumentException("Unknown prompt '" + id + "'; use classifier or system");
    }
  }

  @FunctionalInterface
  public interface Source {
    String text() throws IOException;
  }

  public record Revision(String kind, String action, String sha256, String savedAt, String note) {}

  public record Preset(
      String id, String kind, String title, String file, String description, String source) {}

  public record Presets(List<Preset> presets) {}

  public static final int LIMIT = 32_000;

  private final Path root;

  public PromptStore(Path root) {
    this.root = root.toAbsolutePath();
  }

  /** {@code -Dheelcode.policy.home} or {@code HEELCODE_POLICY_HOME}, else {@code ~/.heelcode/policy}. */
  public static PromptStore open() {
    return new PromptStore(home());
  }

  public static Path home() {
    var configured =
        System.getProperty("heelcode.policy.home", System.getenv("HEELCODE_POLICY_HOME"));
    if (configured != null && !configured.isBlank()) return Path.of(configured);
    return Path.of(System.getProperty("user.home"), ".heelcode", "policy");
  }

  public Path root() {
    return root;
  }

  public Path file(Kind kind) {
    return root.resolve("prompts/" + kind.id + ".txt");
  }

  public static String defaultText(Kind kind) {
    return resource("prompts/" + kind.id + ".txt");
  }

  /** The active text: the saved override when present, otherwise the packaged default. */
  public String text(Kind kind) throws IOException {
    var file = file(kind);
    return Files.exists(file) ? normalize(Files.readString(file)) : defaultText(kind);
  }

  public boolean custom(Kind kind) {
    return Files.exists(file(kind));
  }

  public Revision save(Kind kind, String text, String note) throws IOException {
    var normalized = normalize(text);
    var sha = Course.hash(normalized);
    var archive = root.resolve("prompts/history/" + kind.id + "/" + sha + ".txt");
    if (!Files.exists(archive)) write(archive, normalized);
    var restoring = normalized.equals(defaultText(kind));
    // Saving the default removes the override so later packaged defaults still apply.
    if (restoring) Files.deleteIfExists(file(kind));
    if (!restoring) write(file(kind), normalized);
    var revision =
        new Revision(
            kind.id,
            restoring ? "default" : "save",
            sha,
            Instant.now().toString(),
            note == null ? "" : note.strip());
    Files.createDirectories(root.resolve("prompts"));
    Files.writeString(
        root.resolve("prompts/history.jsonl"),
        Json.MAPPER.writeValueAsString(revision) + "\n",
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    return revision;
  }

  public Revision restoreDefault(Kind kind) throws IOException {
    return save(kind, defaultText(kind), "Restored packaged default");
  }

  /** Newest first. */
  public List<Revision> history(Kind kind) throws IOException {
    var file = root.resolve("prompts/history.jsonl");
    if (!Files.exists(file)) return List.of();
    var result = new ArrayList<Revision>();
    for (var line : Files.readAllLines(file)) {
      if (line.isBlank()) continue;
      var revision = Json.MAPPER.readValue(line, Revision.class);
      if (revision.kind().equals(kind.id)) result.add(revision);
    }
    Collections.reverse(result);
    return List.copyOf(result);
  }

  public String revision(Kind kind, String sha256) throws IOException {
    if (!sha256.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid revision");
    var archive = root.resolve("prompts/history/" + kind.id + "/" + sha256 + ".txt");
    if (Files.exists(archive)) return normalize(Files.readString(archive));
    if (Course.hash(defaultText(kind)).equals(sha256)) return defaultText(kind);
    throw new IllegalArgumentException("Revision not found: " + sha256.substring(0, 12));
  }

  public static List<Preset> presets(Kind kind) {
    try {
      return Json.MAPPER.readValue(resource("prompts/presets.json"), Presets.class).presets().stream()
          .filter(preset -> preset.kind().equals(kind.id))
          .toList();
    } catch (IOException error) {
      throw new IllegalStateException("Packaged prompt presets are unreadable", error);
    }
  }

  public static String presetText(Preset preset) {
    return preset.file().isBlank()
        ? defaultText(Kind.of(preset.kind()))
        : resource("prompts/" + preset.file());
  }

  /**
   * Advisory checks shown to the editor. The output schema still enforces structure; these flag
   * guidance the model would otherwise never receive.
   */
  public static List<String> lint(Kind kind, String text) {
    var warnings = new ArrayList<String>();
    if (kind == Kind.CLASSIFIER) {
      for (var activity : Policy.ACTIVITIES) {
        if (!text.contains(activity))
          warnings.add(
              "Never names the '"
                  + activity
                  + "' category, so the model receives no definition for it.");
      }
      for (var field : List.of("hasAttempt", "unclear", "dishonest")) {
        if (!text.contains(field))
          warnings.add("Never explains the '" + field + "' feature that the schema requires.");
      }
      if (!text.toLowerCase().contains("untrusted"))
        warnings.add(
            "Does not tell the model that request text is untrusted data (prompt-injection risk).");
    }
    if (kind == Kind.SYSTEM) {
      if (!text.toLowerCase().contains("policy"))
        warnings.add(
            "Never refers to the policy. The assignment policy JSON is appended after this text.");
      if (!text.toLowerCase().contains("solution"))
        warnings.add("Does not say whether complete solutions may be produced.");
    }
    if (text.length() > 12_000)
      warnings.add("Long prompt: every request pays for these tokens before the student's text.");
    return List.copyOf(warnings);
  }

  public static String normalize(String text) {
    if (text == null) throw new IllegalArgumentException("Prompt is empty");
    var value = text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    if (value.isBlank()) throw new IllegalArgumentException("Prompt is empty");
    if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("Prompt contains NUL");
    if (value.length() + 1 > LIMIT)
      throw new IllegalArgumentException("Prompt exceeds " + LIMIT + " characters");
    return value + "\n";
  }

  private static void write(Path path, String text) throws IOException {
    Files.createDirectories(path.getParent());
    var temp = Files.createTempFile(path.getParent(), ".prompt-", ".tmp");
    try {
      Files.writeString(temp, text);
      Files.move(
          temp,
          path,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static String resource(String name) {
    try (InputStream input = PromptStore.class.getResourceAsStream(name)) {
      if (input == null) throw new IllegalStateException("Missing packaged prompt: " + name);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      throw new IllegalStateException("Unreadable packaged prompt: " + name, error);
    }
  }
}

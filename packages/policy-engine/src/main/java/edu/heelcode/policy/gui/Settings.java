package edu.heelcode.policy.gui;

import edu.heelcode.policy.Json;
import edu.heelcode.policy.Models;
import edu.heelcode.policy.PromptStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Per-user app preferences. Never stores credentials. */
final class Settings {
  String workspace = "";
  List<String> recent = new ArrayList<>();
  String theme = "dark";
  String classifier = Models.CLASSIFIERS.get(0).id();
  String answer = Models.ANSWERS.get(0).id();
  boolean inspector = true;

  static Path file() {
    return PromptStore.home().resolve("gui.json");
  }

  static Settings load() {
    var settings = new Settings();
    try {
      if (!Files.exists(file())) return settings;
      var node = Json.MAPPER.readTree(Files.readString(file()));
      settings.workspace = node.path("workspace").asText("");
      settings.theme = node.path("theme").asText("dark");
      settings.classifier = node.path("classifier").asText(settings.classifier);
      settings.answer = node.path("answer").asText(settings.answer);
      settings.inspector = node.path("inspector").asBoolean(true);
      node.path("recent").forEach(item -> settings.recent.add(item.asText()));
    } catch (Exception ignored) {
      // Damaged preferences fall back to defaults; they hold nothing that needs recovery.
    }
    return settings;
  }

  void save() {
    try {
      Json.write(
          file(),
          Map.of(
              "workspace", workspace,
              "recent", recent,
              "theme", theme,
              "classifier", classifier,
              "answer", answer,
              "inspector", inspector));
    } catch (Exception ignored) {
      // Preferences are a convenience; failing to save them must not interrupt the user.
    }
  }

  void remember(Path path) {
    var value = path.toAbsolutePath().toString();
    workspace = value;
    recent.remove(value);
    recent.add(0, value);
    while (recent.size() > 6) recent.remove(recent.size() - 1);
    save();
  }
}

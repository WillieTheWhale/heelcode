package edu.heelcode.policy.gui;

import edu.heelcode.policy.Fixtures;
import edu.heelcode.policy.Threads;
import edu.heelcode.policy.Workspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Shared app state: the open course workspace, its active policy, and its saved threads. */
final class State {
  final Settings settings;
  final boolean student;
  Path workspace;
  Workspace.Active active;
  String problem = "";
  List<Threads.Summary> threads = List.of();
  String thread = "";
  private final List<Runnable> listeners = new ArrayList<>();

  State(Settings settings, boolean student) {
    this.settings = settings;
    this.student = student;
  }

  void listen(Runnable listener) {
    listeners.add(listener);
  }

  /** Drops listeners of a window that is being rebuilt (e.g. after a theme switch). */
  void clear() {
    listeners.clear();
  }

  void open(Path path) {
    workspace = path == null ? null : path.toAbsolutePath().normalize();
    thread = "";
    reload();
    if (workspace != null && active != null) settings.remember(workspace);
    changed();
  }

  /** Re-reads the active policy (re-verifying sources) and the thread list. */
  void reload() {
    active = null;
    problem = "";
    threads = List.of();
    if (workspace == null) return;
    try {
      if (!Files.exists(workspace.resolve(".heelcode-policy/active.json"))) {
        problem = "No policy is active in this folder yet. Generate one in Policies, or open the SYS 301 demo.";
        return;
      }
      active = Workspace.active(workspace);
      threads = Threads.list(workspace);
    } catch (Exception error) {
      problem = Ui.message(error);
    }
  }

  void refreshThreads() {
    try {
      if (workspace != null && active != null) threads = Threads.list(workspace);
    } catch (Exception ignored) {
      // Keep the previous list; the next successful refresh replaces it.
    }
    changed();
  }

  void select(String id) {
    thread = id;
    changed();
  }

  void changed() {
    for (var listener : List.copyOf(listeners)) listener.run();
  }

  String course() {
    return active == null ? "" : active.course().courseId().toUpperCase().replaceAll("^([A-Z]+)(\\d)", "$1 $2");
  }

  static Path demoPath() {
    return edu.heelcode.policy.PromptStore.home().resolve("workspaces/sys301-demo");
  }

  static boolean demoAvailable() {
    return Fixtures.sys301Run() != null;
  }
}

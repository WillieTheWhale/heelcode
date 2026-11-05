package edu.heelcode.policy.gui;

import java.awt.GraphicsEnvironment;
import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Desktop entry point: {@code heelcode policy gui [WORKSPACE] [--student] [--thread hps_…]}.
 * {@code --snapshot DIR} renders each view to PNG without showing a window (for documentation).
 */
public final class App {
  public static void launch(String[] args) throws Exception {
    var student = false;
    Path snapshot = null;
    Path workspace = null;
    var thread = "";
    var views = new ArrayList<String>();
    var sends = new ArrayList<String>();
    var suites = new ArrayList<String>();
    var policySteps = new ArrayList<String[]>();
    String assignment = null;
    String classifier = null;
    String answer = null;
    String theme = null;
    for (int index = 0; index < args.length; index++) {
      switch (args[index]) {
        case "--student" -> student = true;
        case "--snapshot" -> snapshot = Path.of(args[++index]);
        case "--view" -> views.add(args[++index]);
        case "--thread" -> thread = args[++index];
        case "--send" -> sends.add(args[++index]);
        case "--suite" -> suites.add(args[++index]);
        case "--generate", "--review", "--edit", "--activate", "--note" -> policySteps.add(new String[] {args[index], args[++index]});
        case "--assignment" -> assignment = args[++index];
        case "--classifier" -> classifier = args[++index];
        case "--answer" -> answer = args[++index];
        case "--theme" -> theme = args[++index];
        default -> workspace = Path.of(args[index]);
      }
    }
    if (snapshot == null && (!sends.isEmpty() || !suites.isEmpty() || !policySteps.isEmpty()))
      throw new IllegalArgumentException("--send, --suite, --generate, --review, and --edit are scripted snapshot steps; add --snapshot DIR");
    // Offscreen snapshot runs stay out of the Dock and never show a window.
    if (snapshot != null) System.setProperty("apple.awt.UIElement", "true");
    System.setProperty("apple.laf.useScreenMenuBar", "true");
    System.setProperty("apple.awt.application.name", "HeelCode");
    System.setProperty("apple.awt.application.appearance", "system");
    if (GraphicsEnvironment.isHeadless())
      throw new IllegalStateException("The HeelCode app needs a desktop session");
    var settings = Settings.load();
    if (classifier != null) settings.classifier = classifier;
    if (answer != null) settings.answer = answer;
    if (theme != null) settings.theme = theme;
    var state = new State(settings, student);
    var requested = workspace;
    var selected = thread;
    var output = snapshot;
    var frames = new MainFrame[1];
    SwingUtilities.invokeAndWait(
        () -> {
          Theme.install(!settings.theme.equals("light"));
          try {
            if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar().setIconImage(Brand.icon(512));
          } catch (UnsupportedOperationException | SecurityException ignored) {
            // Dock icons are cosmetic.
          }
          var path = requested != null ? requested : settings.workspace.isBlank() ? null : Path.of(settings.workspace);
          state.open(path);
          if (!selected.isEmpty()) state.select(selected);
          var frame = new MainFrame(state);
          frames[0] = frame;
          if (output == null) frame.setVisible(true);
          else frame.addNotify();
        });
    if (output == null) return;
    // Scripted snapshot: drive the real views off the event thread, then render offscreen.
    var frame = frames[0];
    var target = assignment;
    for (var text : sends) {
      SwingUtilities.invokeAndWait(() -> frame.ask.submit(target, text));
      waitFor(() -> !frame.ask.busy());
    }
    var policies = (PolicyView) frame.views.get("policies");
    var note = new String[] {""};
    for (var step : policySteps) {
      switch (step[0]) {
        case "--generate" -> {
          SwingUtilities.invokeAndWait(() -> {
            frame.show("policies");
            policies.generateWith(step[1]);
          });
          waitFor(() -> !policies.generating());
        }
        case "--review" -> SwingUtilities.invokeAndWait(() -> {
          frame.show("policies");
          policies.openRun(Path.of(step[1]));
        });
        case "--note" -> note[0] = step[1];
        case "--activate" -> SwingUtilities.invokeAndWait(() -> policies.activateTo(Path.of(step[1]), note[0]));
        default -> {
          var cell = step[1].split(":");
          SwingUtilities.invokeAndWait(() -> policies.editCell(cell[0], cell[1], cell[2], cell.length > 3 && cell[3].equals("attempt")));
        }
      }
    }
    for (var suite : suites) {
      var parts = suite.split(":", 2);
      var tests = (TestsView) frame.views.get("tests");
      SwingUtilities.invokeAndWait(() -> {
        frame.show("tests");
        tests.runFor(parts[0], parts.length > 1 ? parts[1] : edu.heelcode.policy.TestBench.RECORDED);
      });
      waitFor(() -> !tests.running());
    }
    SwingUtilities.invokeAndWait(() -> {
      try {
        snapshot(frame, output, views.isEmpty() ? List.copyOf(frame.views.keySet()) : views);
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
    });
    System.exit(0);
  }

  private static void waitFor(java.util.function.BooleanSupplier done) throws Exception {
    var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(8);
    Thread.sleep(300);
    while (System.nanoTime() < deadline) {
      var finished = new boolean[1];
      SwingUtilities.invokeAndWait(() -> finished[0] = done.getAsBoolean());
      if (finished[0]) {
        SwingUtilities.invokeAndWait(() -> {});
        Thread.sleep(400);
        return;
      }
      Thread.sleep(250);
    }
    throw new IllegalStateException("Scripted step did not finish within 8 minutes");
  }

  private static void snapshot(MainFrame frame, Path directory, List<String> views) throws Exception {
    Files.createDirectories(directory);
    for (var view : views) {
      frame.show(view);
      frame.invalidate();
      frame.validate();
      layout(frame.getRootPane());
      var root = frame.getRootPane();
      var image = new BufferedImage(root.getWidth() * 2, root.getHeight() * 2, BufferedImage.TYPE_INT_ARGB);
      var g = image.createGraphics();
      g.scale(2, 2);
      root.printAll(g);
      g.dispose();
      var file = directory.resolve(view + "-" + (Theme.p.dark ? "dark" : "light") + ".png");
      ImageIO.write(image, "png", file.toFile());
      System.out.println(file);
    }
  }

  private static void layout(java.awt.Container container) {
    container.invalidate();
    container.validate();
    for (var child : container.getComponents())
      if (child instanceof java.awt.Container nested) layout(nested);
    container.validate();
  }
}

package edu.heelcode.policy.gui;

import edu.heelcode.policy.Bridge;
import edu.heelcode.policy.Course;
import edu.heelcode.policy.Fixtures;
import edu.heelcode.policy.Models;
import edu.heelcode.policy.Policy;
import edu.heelcode.policy.PromptStore;
import edu.heelcode.policy.Threads;
import edu.heelcode.policy.Workspace;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.RadialGradientPaint;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

/**
 * The student view, laid out like a Codex thread. Every request is classified and checked
 * against the assignment policy before any answer model runs; a decline is answered in plain
 * prose, with a card (shaped like Codex's "Edited files" card) listing the help that is allowed.
 */
final class AskView extends JPanel implements MainFrame.Refreshable {
  private static final int COLUMN = 720;
  private static final int CARD = 292;
  private static final java.util.Map<String, String> BRIEF = java.util.Map.of(
      "concept", "concepts", "hint", "hints", "debug", "debugging help", "test_design", "test ideas", "test_code", "runnable tests",
      "pseudocode", "pseudocode", "implementation", "solution code", "full_solution", "full solutions", "writeup", "writeups", "logistics", "logistics");
  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.US).withZone(ZoneId.systemDefault());
  private final MainFrame frame;
  private final State state;
  private final ExecutorService serial =
      Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "heelcode-policy-chat");
        thread.setDaemon(true);
        return thread;
      });
  private final CardLayout cards = new CardLayout();
  private final JPanel center = new JPanel(cards);
  private final Ui.Stack transcript = new Ui.Stack(0);
  private final JScrollPane scroll = Ui.fading(transcript, Theme.p.bg);
  private final JButton down = new ScrollDown();
  private final Ui.Stack welcome = new Ui.Stack(0);
  private final Ui.Stack setup = new Ui.Stack(0);
  private final Composer composer = new Composer();
  private final Ui.Stack dock = new Ui.Stack(0);
  private final JPanel bottom = new JPanel(new BorderLayout());
  private final PolicyCard card = new PolicyCard();
  private final JPanel gutter;
  private boolean busy;
  private String assignment = "";
  private String rendered;
  private String policyDigest = "";
  private String threadTitle = "New chat";

  AskView(MainFrame frame) {
    super(new BorderLayout());
    this.frame = frame;
    this.state = frame.state;
    setOpaque(false);

    transcript.maxWidth = COLUMN;
    transcript.setBorder(Ui.pad(16, 32, 32, 32));
    center.setOpaque(false);
    center.add(centered(welcome), "empty");
    center.add(threadArea(), "thread");
    center.add(centered(setup), "setup");
    var main = new JPanel(new BorderLayout());
    main.setOpaque(false);
    main.add(center, BorderLayout.CENTER);
    bottom.setOpaque(false);
    bottom.setBorder(Ui.pad(0, 32, 12, 32));
    dock.maxWidth = COLUMN;
    dock.add(composer);
    bottom.add(dock, BorderLayout.CENTER);
    main.add(bottom, BorderLayout.SOUTH);
    add(main, BorderLayout.CENTER);
    gutter = new JPanel(null) {
      @Override
      public void doLayout() {
        card.setBounds(getWidth() - CARD - 8, 8, CARD, card.heightFor(CARD));
      }
    };
    gutter.setOpaque(false);
    gutter.setPreferredSize(new Dimension(CARD + 12, 10));
    gutter.add(card);
    add(gutter, BorderLayout.EAST);

    composer.onSend = this::send;
    composer.plus.addActionListener(event -> helpMenu());
    composer.policy.onClick(this::classifierMenu);
    composer.model.onClick(this::answerMenu);
    composer.course.onClick(() -> courseMenu(composer.course));
    composer.assignment.onClick(this::assignmentMenu);
    composer.settings.addActionListener(event -> frame.show("admin"));
    composer.settings.setVisible(!state.student);
    state.listen(this::sync);
    sync();
  }

  @Override
  public void refresh() {
    sync();
  }

  void toggleCard() {
    state.settings.inspector = !state.settings.inspector;
    state.settings.save();
    gutter.setVisible(state.settings.inspector && state.active != null);
    revalidate();
    repaint();
    title();
  }

  void focusComposer() {
    composer.input.requestFocusInWindow();
  }

  /** Scripted use (documentation snapshots): the same path as pressing Enter in the composer. */
  void submit(String assignment, String text) {
    if (assignment != null && state.active != null && state.active.policy().scope(assignment) != null) this.assignment = assignment;
    updateControls();
    send(text);
  }

  boolean busy() {
    return busy;
  }

  // ------------------------------------------------------------------ state → view

  private void sync() {
    var active = state.active;
    var digest = active == null ? "" : active.policyDigest();
    if (!digest.equals(policyDigest)) {
      policyDigest = digest;
      assignment = active == null ? "" : defaultAssignment(active.policy());
    }
    if (active == null) {
      threadTitle = state.workspace == null ? "Welcome to HeelCode" : state.workspace.getFileName().toString();
      buildSetup();
      cards.show(center, "setup");
      bottom.setVisible(false);
      gutter.setVisible(false);
      rendered = null;
      title();
      return;
    }
    if (!state.thread.isEmpty() && !state.thread.equals(rendered)) load(state.thread);
    if (state.thread.isEmpty() && !"".equals(rendered)) {
      rendered = "";
      transcript.removeAll();
      threadTitle = "New chat";
      showWelcome();
    }
    updateControls();
    card.update();
    gutter.setVisible(state.settings.inspector);
    title();
  }

  private void title() {
    if (!"ask".equals(frame.view)) return;
    if (state.active == null) {
      frame.title(threadTitle, "");
      return;
    }
    var more = MainFrame.toolbarIcon("dots", "More");
    more.addActionListener(event -> moreMenu(more));
    var toggle = MainFrame.toolbarToggle("panel", "Policy card (⌘I)", state.settings.inspector);
    toggle.addActionListener(event -> toggleCard());
    var scope = scope();
    frame.title(threadTitle, state.course() + (scope == null ? "" : " · " + label(scope)), more, toggle);
  }

  private void showWelcome() {
    buildWelcome();
    composer.tray(true);
    dockItems(true);
    cards.show(center, "empty");
    bottom.setVisible(true);
    revalidate();
    repaint();
  }

  private void showThread() {
    composer.tray(false);
    dockItems(false);
    cards.show(center, "thread");
    bottom.setVisible(true);
    revalidate();
    repaint();
  }

  private void dockItems(boolean welcoming) {
    dock.removeAll();
    if (welcoming && state.active != null && !state.settings.dismissed.equals(tipKey())) dock.add(Ui.gap(new Tip(), 10));
    dock.add(composer);
    dock.revalidate();
    dock.repaint();
  }

  private String tipKey() {
    return policyDigest + ":" + assignment;
  }

  private void load(String id) {
    try {
      var thread = Threads.load(state.workspace, id);
      rendered = id;
      assignment = thread.summary().assignment();
      state.settings.answer = thread.summary().model();
      threadTitle = thread.summary().title();
      start(thread.summary().created());
      for (var entry : thread.entries()) {
        right(new Bubble(entry.prompt()), 34);
        var admission = new Workspace.Response(entry.requestId(), id, 0, state.active.course().courseId(), state.active.policyDigest(), entry.features(), entry.decision());
        addAdmission(admission, "", -1);
        if (entry.status().equals("completed")) append(new Answer(entry.text(), Models.label(thread.summary().model()), -1), 34);
        else if (!List.of("blocked", "admission").contains(entry.status())) append(new Notice(failureTitle(entry.status()), entry.error()), 34);
      }
      showThread();
      scrollToBottom();
    } catch (Exception error) {
      frame.toast("Could not open chat: " + Ui.message(error), Theme.p.deny);
      state.thread = "";
    }
  }

  private void updateControls() {
    var scope = scope();
    composer.course.set("folder", state.course(), null, null);
    composer.assignment.set("book", scope == null ? "Assignment" : label(scope), null, null);
    var classifier = Models.label(state.settings.classifier);
    composer.policy.set("shield", "Policy · " + classifier, null, Theme.unc());
    composer.policy.setToolTipText("Each request is checked against the " + scopeName() + " policy by " + classifier + " before any answer model runs");
    var checkOnly = state.settings.answer.equals(Models.ADMISSION_ONLY);
    composer.model.set(null, checkOnly ? "Policy check only" : Models.label(state.settings.answer), checkOnly ? null : "answers", null);
    composer.model.setToolTipText(checkOnly ? "No answer model is called" : "Answer model: " + Models.label(state.settings.answer));
    composer.placeholder = scope == null ? Composer.PLACEHOLDER : "Ask HeelCode about " + scopeName();
    composer.input.repaint();
  }

  private Policy.Scope scope() {
    return state.active == null ? null : state.active.policy().scope(assignment);
  }

  private String scopeName() {
    var scope = scope();
    return scope == null ? "course" : scope.id().equals("*") ? state.course() : Ui.assignment(scope.id());
  }

  private static String defaultAssignment(Policy policy) {
    return policy.scopes().stream().map(Policy.Scope::id).filter(id -> !id.equals("*")).findFirst().orElse("*");
  }

  private static String label(Policy.Scope scope) {
    if (scope.id().equals("*")) return "General question";
    var name = scope.title().replaceFirst("(?i)^assignment\\s+" + java.util.regex.Pattern.quote(scope.id()) + "\\s*[:—-]\\s*", "");
    return Ui.assignment(scope.id()) + " · " + (name.length() > 26 ? name.substring(0, 25) + "…" : name);
  }

  // ------------------------------------------------------------------ menus

  private void assignmentMenu() {
    if (state.active == null) return;
    var menu = new JPopupMenu();
    for (var scope : state.active.policy().scopes()) {
      var item = new JMenuItem(scope.id().equals("*") ? "General course question" : Ui.assignment(scope.id()) + "  ·  " + scope.title(), scope.id().equals(assignment) ? Ui.icon("check", 14, Theme.p.text) : null);
      item.addActionListener(event -> {
        if (scope.id().equals(assignment)) return;
        assignment = scope.id();
        restartIfNeeded("Switched to " + Ui.assignment(scope.id()) + " — new chat");
      });
      menu.add(item);
    }
    above(menu, composer.assignment);
  }

  private void courseMenu(JComponent anchor) {
    courseMenu(anchor, 0);
  }

  private void courseMenu(JComponent anchor, int x) {
    var menu = new JPopupMenu();
    var open = new JMenuItem("Open course…", Ui.icon("folder", 15, Theme.p.muted));
    open.addActionListener(event -> frame.openWorkspace());
    menu.add(open);
    if (State.demoAvailable()) {
      var demo = new JMenuItem("Open SYS 301 demo", Ui.icon("spark", 15, Theme.p.muted));
      demo.addActionListener(event -> frame.createDemo());
      menu.add(demo);
    }
    var recent = state.settings.recent.stream().filter(path -> state.workspace == null || !path.equals(state.workspace.toString())).toList();
    if (!recent.isEmpty()) menu.addSeparator();
    for (var path : recent) {
      var item = new JMenuItem(Path.of(path).getFileName().toString(), Ui.icon("folder", 15, Theme.p.muted));
      item.setEnabled(Files.isDirectory(Path.of(path)));
      item.addActionListener(event -> {
        state.open(Path.of(path));
        frame.show("ask");
      });
      menu.add(item);
    }
    if (anchor == composer.course) above(menu, anchor);
    else menu.show(anchor, x, anchor.getHeight() + 4);
  }

  /** "+": the kinds of help, marked with what this assignment's policy does with each. */
  private void helpMenu() {
    var scope = scope();
    if (scope == null) return;
    var menu = new JPopupMenu();
    for (var activity : Policy.ACTIVITIES) {
      var rule = scope.rules().stream().filter(r -> r.activities().contains(activity)).findFirst().orElse(null);
      if (rule == null) continue;
      var effect = rule.effect();
      var icon = switch (effect) {
        case "allow" -> "check";
        case "deny" -> "ban";
        default -> "help";
      };
      var note = effect.equals("allow") ? (rule.requireAttempt() ? "with your attempt" : "allowed") : effect.equals("deny") ? "declined" : "ask your instructor";
      var item = new JMenuItem("<html>" + Ui.ACTIVITY.getOrDefault(activity, activity) + "&nbsp;&nbsp;<span style='color:" + Theme.hex(Theme.p.faint) + "'>" + note + "</span></html>", Ui.icon(icon, 14, Theme.effect(effect)));
      item.setEnabled(effect.equals("allow"));
      item.addActionListener(event -> composer.prefill(template(activity)));
      menu.add(item);
    }
    above(menu, composer.plus);
  }

  private void classifierMenu() {
    var menu = new JPopupMenu();
    for (var choice : Models.CLASSIFIERS) {
      var reason = Models.unavailable(choice.id());
      var item = new JMenuItem("<html>" + choice.label() + "&nbsp;&nbsp;<span style='color:" + Theme.hex(Theme.p.faint) + "'>" + (reason == null ? choice.detail() : reason) + "</span></html>", choice.id().equals(state.settings.classifier) ? Ui.icon("check", 14, Theme.p.text) : null);
      item.setEnabled(reason == null);
      item.addActionListener(event -> {
        state.settings.classifier = choice.id();
        state.settings.save();
        updateControls();
        card.update();
      });
      menu.add(item);
    }
    if (Models.unavailable(Models.JEV) != null) {
      menu.addSeparator();
      var key = new JMenuItem("Use a TypeSafe key for this session…");
      key.addActionListener(event -> sessionKey());
      menu.add(key);
    }
    above(menu, composer.policy);
  }

  private void answerMenu() {
    var menu = new JPopupMenu();
    for (var choice : Models.ANSWERS) {
      var item = new JMenuItem("<html>" + choice.label() + "&nbsp;&nbsp;<span style='color:" + Theme.hex(Theme.p.faint) + "'>" + choice.detail() + "</span></html>", choice.id().equals(state.settings.answer) ? Ui.icon("check", 14, Theme.p.text) : null);
      item.addActionListener(event -> {
        if (choice.id().equals(state.settings.answer)) return;
        state.settings.answer = choice.id();
        state.settings.save();
        restartIfNeeded("Answer mode changed — new chat");
      });
      menu.add(item);
    }
    above(menu, composer.model);
  }

  private void moreMenu(JComponent anchor) {
    var menu = new JPopupMenu();
    if (!state.thread.isEmpty()) {
      var copy = new JMenuItem("Copy chat ID", Ui.icon("copy", 15, Theme.p.muted));
      copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(state.thread), null));
      menu.add(copy);
    }
    var digest = new JMenuItem("Copy policy digest", Ui.icon("shield", 15, Theme.p.muted));
    digest.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(policyDigest), null));
    menu.add(digest);
    if (!state.student) {
      menu.addSeparator();
      var policies = new JMenuItem("Open policies", Ui.icon("layers", 15, Theme.p.muted));
      policies.addActionListener(event -> frame.show("policies"));
      menu.add(policies);
      var prompts = new JMenuItem("Edit admin prompts", Ui.icon("sliders", 15, Theme.p.muted));
      prompts.addActionListener(event -> frame.show("admin"));
      menu.add(prompts);
    }
    menu.show(anchor, anchor.getWidth() - menu.getPreferredSize().width, anchor.getHeight() + 4);
  }

  private static void above(JPopupMenu menu, JComponent anchor) {
    menu.show(anchor, 0, -menu.getPreferredSize().height - 6);
  }

  private void sessionKey() {
    var field = new javax.swing.JPasswordField(36);
    var result = javax.swing.JOptionPane.showConfirmDialog(frame, new Object[] {"TypeSafe API key (kept in memory for this session only):", field}, "Jev classifier", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
    if (result != javax.swing.JOptionPane.OK_OPTION || field.getPassword().length == 0) return;
    System.setProperty("heelcode.typesafe.key", new String(field.getPassword()));
    state.settings.classifier = Models.JEV;
    state.settings.save();
    updateControls();
    card.update();
    frame.toast("Jev is available for this session", Theme.p.allow);
  }

  /** Sessions are pinned to one assignment and answer model, so a change starts a new chat. */
  private void restartIfNeeded(String message) {
    if (!state.thread.isEmpty()) {
      state.select("");
      frame.toast(message, Theme.p.accent);
    }
    updateControls();
    card.update();
    if (state.thread.isEmpty()) showWelcome();
    title();
  }

  // ------------------------------------------------------------------ sending

  private void send(String text) {
    if (busy || state.active == null) return;
    var unavailable = Models.unavailable(state.settings.classifier);
    if (unavailable != null) {
      frame.toast(Models.label(state.settings.classifier) + " is unavailable: " + unavailable, Theme.p.clarify);
      return;
    }
    busy = true;
    composer.setBusy(true);
    composer.clear();
    if (transcript.getComponentCount() == 0) {
      threadTitle = text.length() > 60 ? text.substring(0, 59) + "…" : text;
      start(Instant.now());
      title();
    }
    showThread();
    rendered = state.thread;
    right(new Bubble(text), 34);
    var classifierLabel = Models.label(state.settings.classifier);
    var checking = new Working("Checking against the " + scopeName() + " policy");
    append(checking, 34);
    refreshTranscript();

    var workspace = state.workspace;
    var request = new Workspace.Request("r" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6), state.thread, assignment, text);
    var classifier = state.settings.classifier;
    var answerModel = state.settings.answer;
    var started = System.nanoTime();
    serial.submit(() -> {
      try {
        var extractor = Workspace.model(workspace, classifier);
        if (answerModel.equals(Models.ADMISSION_ONLY)) {
          var admission = Workspace.check(workspace, request, extractor);
          var elapsed = (System.nanoTime() - started) / 1_000_000;
          SwingUtilities.invokeLater(() -> {
            transcript.remove(checking);
            addAdmission(admission, classifierLabel, elapsed);
            finish(admission.sessionId());
          });
          return;
        }
        var admitted = new long[] {0};
        var answering = new Working[1];
        var response =
            Bridge.chat(workspace, request, extractor, answerModel, Bridge.heelcode(executable(), answerModel), admission -> {
              admitted[0] = System.nanoTime();
              var elapsed = (admitted[0] - started) / 1_000_000;
              try {
                SwingUtilities.invokeAndWait(() -> {
                  transcript.remove(checking);
                  addAdmission(admission, classifierLabel, elapsed);
                  if (admission.decision().forward()) {
                    answering[0] = new Working("Answering with " + Models.label(answerModel));
                    append(answering[0], 34);
                  }
                  refreshTranscript();
                });
              } catch (Exception ignored) {
                // Display only; the bridge continues regardless.
              }
            });
        var answerMillis = admitted[0] == 0 ? -1 : (System.nanoTime() - admitted[0]) / 1_000_000;
        SwingUtilities.invokeLater(() -> {
          transcript.remove(checking);
          if (answering[0] != null) transcript.remove(answering[0]);
          if (admitted[0] == 0 && response.policy() != null) addAdmission(response.policy(), classifierLabel, -1);
          switch (response.status()) {
            case "completed" -> append(new Answer(response.text(), Models.label(answerModel), answerMillis), 34);
            case "blocked" -> {}
            default -> append(new Notice(failureTitle(response.status()), response.error()), 34);
          }
          finish(response.sessionId());
        });
      } catch (Exception error) {
        SwingUtilities.invokeLater(() -> {
          transcript.remove(checking);
          append(new Notice("Not sent — nothing reached an answer model", Ui.message(error)), 34);
          finish(state.thread);
        });
      }
    });
  }

  private static String failureTitle(String status) {
    return switch (status) {
      case "failed" -> "The answer model failed. The policy decision is saved.";
      case "uncertain" -> "The answer's outcome is unknown and won't be retried automatically.";
      case "busy" -> "This chat is busy. Try again in a moment.";
      case "pending" -> "No answer was saved for this turn.";
      default -> "The request could not continue.";
    };
  }

  private void finish(String sessionId) {
    busy = false;
    composer.setBusy(false);
    refreshTranscript();
    if (sessionId != null && !sessionId.isEmpty()) {
      rendered = sessionId;
      state.thread = sessionId;
    }
    state.refreshThreads();
    frame.threadChanged();
    focusComposer();
  }

  private void refreshTranscript() {
    transcript.revalidate();
    transcript.repaint();
    scrollToBottom();
  }

  private void scrollToBottom() {
    SwingUtilities.invokeLater(() -> {
      transcript.revalidate();
      var bar = scroll.getVerticalScrollBar();
      SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
    });
  }

  static String executable() {
    var configured = System.getenv("HEELCODE_EXECUTABLE");
    if (configured != null && !configured.isBlank()) return configured;
    var root = Fixtures.packageRoot();
    if (root != null) {
      var launcher = root.resolve("../../bin/heelcode").normalize();
      if (Files.isExecutable(launcher)) return launcher.toString();
    }
    return "heelcode";
  }

  // ------------------------------------------------------------------ thread pieces

  private void start(Instant when) {
    transcript.removeAll();
    var date = MainFrame.centered(DATE.format(when), Theme.ui(13f), Theme.p.faint);
    date.setPreferredSize(new Dimension(10, 20));
    append(date, 18);
  }

  private void append(JComponent component, int gap) {
    transcript.add(Ui.gap(component, gap));
  }

  private void right(JComponent component, int gap) {
    transcript.add(Ui.gap(component, gap), Ui.Align.RIGHT);
  }

  private void addAdmission(Workspace.Response admission, String classifier, long millis) {
    append(new Check(admission, classifier, millis), 18);
    if (!admission.decision().forward()) append(new Gate(admission), 34);
  }

  private JComponent threadArea() {
    var area = new JPanel(null) {
      @Override
      public boolean isOptimizedDrawingEnabled() {
        return false;
      }

      @Override
      public void doLayout() {
        scroll.setBounds(0, 0, getWidth(), getHeight());
        down.setBounds((getWidth() - 32) / 2, getHeight() - 42, 32, 32);
      }
    };
    area.setOpaque(false);
    area.add(down);
    area.add(scroll);
    down.setVisible(false);
    down.addActionListener(event -> scrollToBottom());
    scroll.getVerticalScrollBar().addAdjustmentListener(event -> {
      var bar = scroll.getVerticalScrollBar();
      down.setVisible(bar.getValue() + bar.getVisibleAmount() < bar.getMaximum() - 48);
    });
    return area;
  }

  /** The student's turn: a right-aligned rounded bubble in Codex's quiet gray. */
  private static final class Bubble extends Ui.Stack implements Ui.Sized {
    private final JTextPane text;
    private final String message;

    Bubble(String message) {
      super(0);
      this.message = message;
      card(Theme.p.bubble, null);
      arc = 36;
      setBorder(Ui.pad(9, 15, 5, 15));
      text = Ui.prose(message, 14f, Theme.p.text);
      Ui.leading(text, 0.3f);
      add(text);
    }

    @Override
    public int widthFor(int available) {
      var metrics = text.getFontMetrics(text.getFont());
      var widest = 0;
      for (var line : message.split("\n")) widest = Math.max(widest, metrics.stringWidth(line));
      return Math.min((int) (available * 0.72), widest + 36);
    }
  }

  /** Codex's "Worked for …" line: gray, expandable, with a hairline under it. */
  private final class Check extends Ui.Stack {
    private boolean open;
    private boolean hover;

    Check(Workspace.Response admission, String classifier, long millis) {
      super(0);
      var decision = admission.decision();
      var summary = "Checked against the " + scopeName() + " policy" + (classifier.isEmpty() ? "" : " by " + classifier) + (millis >= 0 ? " in " + Ui.seconds(millis) : "");
      var row = new JComponent() {
        @Override
        protected void paintComponent(Graphics graphics) {
          var g = Ui.smooth(graphics);
          g.setFont(Theme.ui(14f));
          g.setColor(hover ? Theme.p.muted : Theme.p.faint);
          var metrics = g.getFontMetrics();
          var y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
          g.drawString(summary, 0, y);
          Ui.icon(open ? "chevron-down" : "chevron-right", 14, hover ? Theme.p.muted : Theme.p.faint).paintIcon(this, g, metrics.stringWidth(summary) + 5, (getHeight() - 14) / 2);
          g.dispose();
        }
      };
      row.setPreferredSize(new Dimension(10, 28));
      Ui.hover(row, () -> { hover = true; row.repaint(); }, () -> { hover = false; row.repaint(); });
      add(row);

      var details = new Ui.Stack(6);
      details.setBorder(Ui.pad(4, 0, 12, 0));
      var features = admission.features();
      var detected = features.activities();
      var verdict = switch (decision.action()) {
        case "allow" -> "Allowed";
        case "deny" -> "Declined";
        default -> "Needs clarification";
      };
      details.add(detail("Decision", verdict + " — " + decision.reason().replace('_', ' ') + (decision.forward() ? ", forwarded to the answer model" : ", not forwarded")));
      details.add(detail("Activities", (detected.isEmpty() ? "none identified" : Ui.join(detected)) + " · attempt " + (features.hasAttempt() ? "shown" : "not shown") + (features.unclear() ? " · unclear" : "") + (features.dishonest() ? " · concealment requested" : "")));
      details.add(detail("Classifier", features.rationale()));
      details.add(detail("Record", "Request " + admission.requestId() + (admission.turn() > 0 ? " · turn " + admission.turn() : "") + " · policy " + Ui.shortSha(admission.policyDigest())));
      details.setVisible(false);
      add(details);
      var rule = new JComponent() {
        @Override
        protected void paintComponent(Graphics g) {
          g.setColor(Theme.p.subtle);
          g.fillRect(0, getHeight() - 1, getWidth(), 1);
        }
      };
      rule.setPreferredSize(new Dimension(10, 7));
      add(rule);
      Ui.click(row, () -> {
        open = !open;
        details.setVisible(open);
        transcript.revalidate();
        transcript.repaint();
      });
    }

    private JComponent detail(String key, String value) {
      var pane = Ui.prose("", 13.5f, Theme.p.faint);
      var document = pane.getStyledDocument();
      var strong = new SimpleAttributeSet();
      StyleConstants.setForeground(strong, Theme.p.muted);
      try {
        document.insertString(0, key + "  ", strong);
        document.insertString(document.getLength(), value, null);
      } catch (BadLocationException ignored) {
        // Offsets come from the document itself.
      }
      Ui.leading(pane, 0.2f);
      return pane;
    }
  }

  /** The reply to a declined or unclear request: prose, the decision card, then the cited rule. */
  private final class Gate extends Ui.Stack {
    Gate(Workspace.Response admission) {
      super(14);
      var decision = admission.decision();
      var scope = scope();
      add(paragraph(headline(decision.reason(), scope), decision.feedback()));
      add(new DecisionCard(admission, scope));
      for (var evidence : decision.evidence().stream().limit(2).toList()) {
        var quote = evidence.quote().replaceAll("\\s+", " ").strip();
        add(new Quote((quote.length() > 240 ? quote.substring(0, 239) + "…" : quote), evidence.source()));
      }
    }
  }

  private static JTextPane paragraph(String lead, String rest) {
    var pane = Ui.prose("", 14f, Theme.p.text);
    var document = pane.getStyledDocument();
    var bold = new SimpleAttributeSet();
    StyleConstants.setBold(bold, true);
    StyleConstants.setForeground(bold, Theme.p.emphasis);
    try {
      document.insertString(0, lead + " ", bold);
      document.insertString(document.getLength(), rest, null);
    } catch (BadLocationException ignored) {
      // Offsets come from the document itself.
    }
    Ui.leading(pane, 0.3f);
    return pane;
  }

  /**
   * Codex's "Edited N files" card, holding a policy decision: an icon tile, the verdict and what
   * was detected, a "View policy" button, then one row per kind of help that is allowed instead.
   */
  private final class DecisionCard extends Ui.Stack {
    DecisionCard(Workspace.Response admission, Policy.Scope scope) {
      super(0);
      var decision = admission.decision();
      var tone = Theme.effect(decision.action());
      card(Theme.p.dark ? Theme.p.sidebar : Color.WHITE, Theme.p.border);
      arc = 28;
      setBorder(Ui.pad(1, 1, 1, 1));
      var header = new JPanel(new BorderLayout(12, 0)) {
        @Override
        protected void paintComponent(Graphics graphics) {
          var g = Ui.smooth(graphics);
          g.setColor(Theme.p.dark ? new Color(0x1B1B1B) : new Color(0xFAFAFA));
          g.fill(MainFrame.rounded(getWidth(), getHeight(), 13, 13, 0, 0));
          g.setColor(Theme.p.subtle);
          g.fillRect(0, getHeight() - 1, getWidth(), 1);
          g.dispose();
        }
      };
      header.setOpaque(false);
      header.setBorder(Ui.pad(12, 12, 12, 12));
      var icon = switch (decision.action()) {
        case "deny" -> "ban";
        case "allow" -> "check";
        default -> "help";
      };
      var tile = new JComponent() {
        @Override
        protected void paintComponent(Graphics graphics) {
          var g = Ui.smooth(graphics);
          g.setColor(Theme.p.dark ? new Color(0x0F0F0F) : new Color(0xF1F1F1));
          g.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
          Ui.icon(icon, 18, tone).paintIcon(this, g, (getWidth() - 18) / 2, (getHeight() - 18) / 2);
          g.dispose();
        }
      };
      tile.setPreferredSize(new Dimension(38, 38));
      header.add(tile, BorderLayout.WEST);
      var words = new JPanel(new BorderLayout(0, 1));
      words.setOpaque(false);
      words.add(Ui.label(verdictTitle(decision.reason(), scope), 14f, Font.PLAIN, Theme.p.emphasis), BorderLayout.NORTH);
      var detected = admission.features().activities();
      var what = detected.isEmpty() ? "No activity identified" : String.join(", ", detected.stream().map(a -> Ui.ACTIVITY.getOrDefault(a, a)).toList());
      words.add(Ui.label(what + " · " + switch (decision.action()) {
        case "deny" -> "declined";
        case "allow" -> "allowed";
        default -> "needs detail";
      }, 13f, Font.PLAIN, tone), BorderLayout.CENTER);
      header.add(words, BorderLayout.CENTER);
      var view = Ui.outline("View policy");
      view.addActionListener(event -> {
        card.open = true;
        if (!state.settings.inspector) toggleCard();
        card.update();
      });
      var holder = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 6));
      holder.setOpaque(false);
      holder.add(view);
      header.add(holder, BorderLayout.EAST);
      header.setPreferredSize(new Dimension(10, 62));
      add(header);

      if (scope == null) return;
      var alternatives = new ArrayList<String[]>();
      for (var activity : Policy.ACTIVITIES) {
        var rule = scope.rules().stream().filter(r -> r.activities().contains(activity)).findFirst().orElse(null);
        if (rule != null && rule.effect().equals("allow")) alternatives.add(new String[] {activity, rule.requireAttempt() ? "With your attempt" : "Allowed"});
      }
      var rows = new Ui.Stack(0);
      rows.setBorder(Ui.pad(5, 0, 5, 0));
      for (var alternative : alternatives.stream().limit(5).toList())
        rows.add(new AlternativeRow(scope.id().equals("*") ? state.course() : Ui.assignment(scope.id()), alternative[0], alternative[1]));
      if (!alternatives.isEmpty()) add(rows);
    }
  }

  /** One allowed alternative; clicking it starts that request in the composer. */
  private final class AlternativeRow extends JComponent {
    private final String scope;
    private final String activity;
    private final String effect;
    private boolean hover;

    AlternativeRow(String scope, String activity, String effect) {
      this.scope = scope;
      this.activity = activity;
      this.effect = effect;
      setToolTipText("Ask for " + Ui.ACTIVITY.getOrDefault(activity, activity).toLowerCase(Locale.ROOT));
      Ui.hover(this, () -> { hover = true; repaint(); }, () -> { hover = false; repaint(); });
      Ui.click(this, () -> composer.prefill(template(activity)));
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(100, 34);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      if (hover) {
        g.setColor(Theme.p.dark ? Theme.alpha(Color.WHITE, 8) : Theme.alpha(Color.BLACK, 8));
        g.fillRect(0, 0, getWidth(), getHeight());
      }
      g.setFont(Theme.ui(14f));
      var metrics = g.getFontMetrics();
      var y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
      var prefix = scope + " / ";
      g.setColor(Theme.p.faint);
      g.drawString(prefix, 14, y);
      g.setColor(hover ? Theme.p.emphasis : Theme.p.text);
      g.drawString(Ui.ACTIVITY.getOrDefault(activity, activity), 14 + metrics.stringWidth(prefix), y);
      g.setFont(Theme.ui(13.5f));
      var small = g.getFontMetrics();
      g.setColor(effect.equals("Allowed") ? Theme.p.allow : Theme.p.clarify);
      g.drawString(effect, getWidth() - 14 - small.stringWidth(effect), (getHeight() - small.getHeight()) / 2 + small.getAscent());
      g.dispose();
    }
  }

  /** A blockquote with a thin left rule, as Codex renders quoted passages. */
  private static final class Quote extends JPanel implements Ui.Flow {
    private final JTextPane text;

    Quote(String body, String source) {
      super(null);
      setOpaque(false);
      text = Ui.prose("", 13.5f, Theme.p.muted);
      var document = text.getStyledDocument();
      var faint = new SimpleAttributeSet();
      StyleConstants.setForeground(faint, Theme.p.faint);
      try {
        document.insertString(0, "“" + body + "”", null);
        document.insertString(document.getLength(), "  — " + source, faint);
      } catch (BadLocationException ignored) {
        // Offsets come from the document itself.
      }
      Ui.leading(text, 0.28f);
      add(text);
    }

    @Override
    public int heightFor(int width) {
      return Ui.heightFor(text, Math.max(40, width - 16));
    }

    @Override
    public void doLayout() {
      text.setBounds(16, 0, Math.max(40, getWidth() - 16), heightFor(getWidth()));
    }

    @Override
    protected void paintComponent(Graphics g) {
      g.setColor(Theme.p.borderStrong);
      g.fillRect(1, 3, 2, getHeight() - 8);
    }
  }

  private static String headline(String reason, Policy.Scope scope) {
    var name = scope == null ? "this assignment" : scope.id().equals("*") ? "this course" : Ui.assignment(scope.id());
    return switch (reason) {
      case "deception" -> "I can't help hide AI use or misrepresent authorship.";
      case "policy_prohibits_activity" -> "That's outside " + name + "'s AI policy.";
      case "attempt_required" -> "Share your attempt first.";
      case "policy_uncertain" -> "Your instructor hasn't settled this one.";
      case "unknown_assignment" -> "Choose an assignment in this course.";
      default -> "Tell me a bit more about what you need.";
    };
  }

  private static String verdictTitle(String reason, Policy.Scope scope) {
    var name = scope == null ? "course" : scope.id().equals("*") ? "course" : Ui.assignment(scope.id());
    return switch (reason) {
      case "deception" -> "Declined: misrepresents authorship";
      case "policy_prohibits_activity" -> "Declined by the " + name + " policy";
      case "attempt_required" -> "Allowed once you share an attempt";
      case "policy_uncertain" -> "Not settled by your instructor";
      case "unknown_assignment" -> "No matching assignment";
      default -> "Needs more detail";
    };
  }

  private static String template(String activity) {
    return switch (activity) {
      case "concept" -> "Can you explain the idea behind ";
      case "hint" -> "Could you give me a conceptual hint about ";
      case "debug" -> "Here's what I tried, what I expected, and what actually happened: ";
      case "test_design" -> "What cases should I think about when testing ";
      case "test_code" -> "Help me write tests for ";
      case "pseudocode" -> "Can you outline an approach for ";
      case "implementation" -> "Help me implement ";
      case "full_solution" -> "Show me a complete worked example of ";
      case "writeup" -> "Help me draft ";
      default -> "Quick course question: ";
    };
  }

  /** A model answer: Markdown prose with a quiet action row. */
  private static final class Answer extends Ui.Stack {
    Answer(String markdown, String model, long millis) {
      super(4);
      add(Markdown.pane(markdown));
      var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      row.setOpaque(false);
      var copy = MainFrame.toolbarIcon("copy", "Copy");
      copy.addActionListener(event -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(markdown), null));
      row.add(copy);
      row.add(Ui.label("  " + model + (millis >= 0 ? " · " + Ui.seconds(millis) : ""), 12.5f, Font.PLAIN, Theme.p.faint));
      row.setPreferredSize(new Dimension(10, 28));
      add(row);
    }
  }

  private static final class Notice extends Ui.Stack {
    Notice(String title, String detail) {
      super(4);
      add(Ui.label(title, 14f, Font.PLAIN, Theme.p.deny));
      if (detail != null && !detail.isBlank()) add(Ui.wrap(detail, 13.5f, Theme.p.faint));
    }
  }

  /** Codex's shimmering status line ("Thinking"), with a live elapsed time. */
  private static final class Working extends JPanel {
    private final JLabel clock = Ui.label("0s", 14f, Font.PLAIN, Theme.p.faint);
    private final Timer timer;

    Working(String text) {
      super(new FlowLayout(FlowLayout.LEFT, 0, 0));
      setOpaque(false);
      add(new Ui.Shimmer(text, 14f));
      clock.setBorder(Ui.pad(0, 8, 0, 0));
      add(clock);
      var started = System.nanoTime();
      timer = new Timer(100, event -> clock.setText(Ui.seconds((System.nanoTime() - started) / 1_000_000)));
      setPreferredSize(new Dimension(10, 24));
    }

    @Override
    public void addNotify() {
      super.addNotify();
      timer.start();
    }

    @Override
    public void removeNotify() {
      timer.stop();
      super.removeNotify();
    }
  }

  /** Codex's round "jump to latest" button, floating above the composer. */
  private static final class ScrollDown extends JButton {
    ScrollDown() {
      setBorderPainted(false);
      setContentAreaFilled(false);
      setFocusPainted(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setToolTipText("Jump to latest");
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      g.setColor(Theme.p.dark ? Theme.p.sidebar : Color.WHITE);
      g.fillOval(1, 1, getWidth() - 3, getHeight() - 3);
      g.setColor(getModel().isRollover() ? Theme.p.borderStrong : Theme.p.border);
      g.drawOval(1, 1, getWidth() - 3, getHeight() - 3);
      Ui.icon("arrow-down", 16, Theme.p.text).paintIcon(this, g, (getWidth() - 16) / 2, (getHeight() - 16) / 2);
      g.dispose();
    }
  }

  // ------------------------------------------------------------------ new chat and setup

  /** Centers a column, shrinking it to fit, a little above the middle like Codex's new chat. */
  private static JComponent centered(Ui.Stack column) {
    var holder = new JPanel(null) {
      @Override
      public void doLayout() {
        var width = Math.max(200, Math.min(getWidth() - 64, column.maxWidth > 0 ? column.maxWidth : COLUMN));
        var height = column.heightFor(width);
        column.setBounds((getWidth() - width) / 2, Math.max(16, (getHeight() - height) / 2 - 20), width, height);
      }
    };
    holder.setOpaque(false);
    holder.add(column);
    return holder;
  }

  private void buildWelcome() {
    welcome.removeAll();
    welcome.maxWidth = COLUMN;
    welcome.add(Ui.gap(new Argyle(52), 22), Ui.Align.CENTER);
    welcome.add(new Heading());
    welcome.revalidate();
    welcome.repaint();
  }

  /** "What should we learn in SYS 301?" with the course as a dotted-underline menu, as in Codex. */
  private final class Heading extends JComponent {
    private int courseLeft;
    private int courseRight;
    private boolean hover;

    Heading() {
      setFont(Theme.ui(26f));
      setPreferredSize(new Dimension(10, 40));
      addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
        @Override
        public void mouseMoved(java.awt.event.MouseEvent event) {
          var over = event.getX() >= courseLeft && event.getX() <= courseRight;
          if (over != hover) {
            hover = over;
            setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            repaint();
          }
        }
      });
      addMouseListener(new java.awt.event.MouseAdapter() {
        @Override
        public void mouseExited(java.awt.event.MouseEvent event) {
          hover = false;
          repaint();
        }

        @Override
        public void mouseClicked(java.awt.event.MouseEvent event) {
          if (event.getX() >= courseLeft && event.getX() <= courseRight) courseMenu(Heading.this, courseLeft);
        }
      });
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      g.setFont(getFont());
      var metrics = g.getFontMetrics();
      var lead = "What should we learn in ";
      var course = state.course();
      var tail = "?";
      var width = metrics.stringWidth(lead + course + tail);
      var x = (getWidth() - width) / 2;
      var y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
      g.setColor(Theme.p.emphasis);
      g.drawString(lead, x, y);
      courseLeft = x + metrics.stringWidth(lead);
      courseRight = courseLeft + metrics.stringWidth(course);
      g.setColor(hover ? Theme.unc() : Theme.p.emphasis);
      g.drawString(course, courseLeft, y);
      g.setColor(Theme.p.emphasis);
      g.drawString(tail, courseRight, y);
      // Codex marks the switchable project name with a dotted underline.
      g.setColor(hover ? Theme.unc() : Theme.p.faint);
      g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {0.1f, 3.4f}, 0f));
      g.drawLine(courseLeft + 1, y + 6, courseRight - 1, y + 6);
      g.dispose();
    }
  }

  /**
   * The new-chat mark: a line-art argyle diamond (UNC's argyle) stitched with a dashed inner
   * diamond in Carolina, holding a terminal prompt — the same role as Codex's line-art glyph.
   */
  private static final class Argyle extends JComponent {
    private final int size;

    Argyle(int size) {
      this.size = size;
      setPreferredSize(new Dimension(size, size));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      var s = size;
      var outer = diamond(s / 2.0, s / 2.0, s / 2.0 - 1.5);
      g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g.setColor(Theme.p.muted);
      g.draw(outer);
      g.setColor(Theme.unc());
      g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {2.2f, 3f}, 0f));
      g.draw(diamond(s / 2.0, s / 2.0, s / 2.0 - 8));
      g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g.setColor(Theme.p.text);
      var cx = s / 2.0;
      var cy = s / 2.0;
      var chevron = new Path2D.Double();
      chevron.moveTo(cx - 7, cy - 4.5);
      chevron.lineTo(cx - 2.5, cy);
      chevron.lineTo(cx - 7, cy + 4.5);
      g.draw(chevron);
      g.setColor(Theme.unc());
      g.draw(new java.awt.geom.Line2D.Double(cx + 0.5, cy + 4.5, cx + 7, cy + 4.5));
      g.dispose();
    }

    private static Path2D diamond(double cx, double cy, double r) {
      var path = new Path2D.Double();
      path.moveTo(cx, cy - r);
      path.lineTo(cx + r, cy);
      path.lineTo(cx, cy + r);
      path.lineTo(cx - r, cy);
      path.closePath();
      return path;
    }
  }

  /** Codex's announcement card above the composer, here saying what this assignment allows. */
  private final class Tip extends JPanel implements Ui.Flow {
    private final JLabel heading;
    private final javax.swing.JTextArea body;
    private final JButton view = Ui.outline("View policy");
    private final JButton close = MainFrame.toolbarIcon("x", "Dismiss");
    private final JComponent orb = new JComponent() {
      @Override
      protected void paintComponent(Graphics graphics) {
        // A Carolina-to-Navy orb, lit from the upper left.
        var g = Ui.smooth(graphics);
        var w = getWidth();
        g.setPaint(new RadialGradientPaint(new Point2D.Double(w * 0.34, w * 0.3), w * 0.78f, new float[] {0f, 0.45f, 1f}, new Color[] {new Color(0xCFE8F8), Theme.CAROLINA, Theme.NAVY}));
        g.fillOval(0, 0, w - 1, w - 1);
        g.setColor(Theme.alpha(Color.WHITE, 40));
        g.drawOval(0, 0, w - 1, w - 1);
        g.dispose();
      }
    };

    Tip() {
      super(null);
      setOpaque(false);
      var scope = scope();
      heading = Ui.label("The " + scopeName() + " AI policy is on", 14f, Font.PLAIN, Theme.p.emphasis);
      body = Ui.wrap(summary(scope), 13.5f, Theme.p.faint);
      view.addActionListener(event -> {
        card.open = true;
        if (!state.settings.inspector) toggleCard();
        card.update();
      });
      close.addActionListener(event -> {
        state.settings.dismissed = tipKey();
        state.settings.save();
        dockItems(true);
      });
      add(orb);
      add(heading);
      add(body);
      add(view);
      add(close);
    }

    private String summary(Policy.Scope scope) {
      if (scope == null) return "";
      var allowed = new ArrayList<String>();
      var declined = new ArrayList<String>();
      for (var activity : Policy.ACTIVITIES) {
        var rule = scope.rules().stream().filter(r -> r.activities().contains(activity)).findFirst().orElse(null);
        if (rule == null) continue;
        var name = BRIEF.getOrDefault(activity, activity);
        if (rule.effect().equals("allow")) allowed.add(name);
        else if (rule.effect().equals("deny")) declined.add(name);
      }
      return "Allowed: " + (allowed.isEmpty() ? "nothing yet" : String.join(", ", allowed)) + ". Declined: " + (declined.isEmpty() ? "nothing" : String.join(", ", declined)) + ".";
    }

    /** The body runs the card's full width, below the buttons that share the heading's row. */
    private int textWidth(int width) {
      return Math.max(120, width - 62 - 22);
    }

    @Override
    public int heightFor(int width) {
      return Math.max(64, 39 + Ui.heightFor(body, textWidth(width)) + 14);
    }

    @Override
    public void doLayout() {
      var width = getWidth();
      orb.setBounds(16, 15, 32, 32);
      var text = textWidth(width);
      heading.setBounds(62, 14, Math.max(80, width - 62 - 12 - 28 - 6 - view.getPreferredSize().width - 10), 20);
      body.setBounds(62, 39, text, Ui.heightFor(body, text));
      close.setBounds(width - 12 - 28, 12, 28, 28);
      var size = view.getPreferredSize();
      view.setBounds(width - 12 - 28 - 6 - size.width, 13, size.width, 26);
    }

    @Override
    public Dimension getPreferredSize() {
      var width = getWidth() > 0 ? getWidth() : COLUMN;
      return new Dimension(width, heightFor(width));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      g.setColor(Theme.p.dark ? new Color(0x191919) : new Color(0xF9F9F9));
      g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 32, 32);
      g.setColor(Theme.p.dark ? Theme.p.border : Theme.p.subtle);
      g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 32, 32);
      g.dispose();
    }
  }

  private void buildSetup() {
    setup.removeAll();
    setup.maxWidth = 560;
    setup.add(Ui.gap(new Argyle(52), 22), Ui.Align.CENTER);
    setup.add(Ui.gap(MainFrame.centered(state.workspace == null ? "Open a course" : "This course isn't ready", Theme.ui(26f), Theme.p.emphasis), 12));
    var explain = Ui.centered("HeelCode answers inside one course at a time. A course folder holds the instructor's activated policy and your chats; nothing reaches an answer model until that policy admits it.", 14.5f, Theme.p.muted);
    setup.add(Ui.gap(explain, 18));
    if (!state.problem.isEmpty()) setup.add(Ui.gap(Ui.centered(state.problem, 14f, Theme.p.clarify), 18));
    var buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
    buttons.setOpaque(false);
    if (State.demoAvailable()) {
      var demo = Ui.primary("Open SYS 301 demo", null);
      demo.addActionListener(event -> frame.createDemo());
      buttons.add(demo);
    }
    var open = Ui.secondary("Open course folder…", null);
    open.addActionListener(event -> frame.openWorkspace());
    buttons.add(open);
    if (!state.student) {
      var generate = Ui.secondary("Generate a policy", null);
      generate.addActionListener(event -> frame.show("policies"));
      buttons.add(generate);
    }
    buttons.setPreferredSize(new Dimension(560, 36));
    setup.add(buttons);
    setup.revalidate();
  }

  // ------------------------------------------------------------------ policy card

  /**
   * Codex's floating project card (top right), for the assignment policy: what it allows and
   * declines (expandable), the answer and check models, and whether admin prompts are custom.
   */
  private final class PolicyCard extends Ui.Stack {
    boolean open;

    PolicyCard() {
      super(0);
      card(Theme.p.raised, Theme.p.dark ? Theme.alpha(Color.WHITE, 9) : Theme.p.border);
      arc = 28;
      setBorder(Ui.pad(6, 8, 8, 8));
    }

    void update() {
      removeAll();
      var active = state.active;
      if (active == null) return;
      var scope = scope();
      var head = new JPanel(new BorderLayout());
      head.setOpaque(false);
      head.setBorder(Ui.pad(0, 12, 0, 0));
      head.add(Ui.label(state.course() + (scope == null ? "" : " · " + (scope.id().equals("*") ? "General" : Ui.assignment(scope.id()))), 14f, Font.PLAIN, Theme.p.muted), BorderLayout.CENTER);
      var more = MainFrame.toolbarIcon("dots", "Policy options");
      more.addActionListener(event -> moreMenu(more));
      head.add(more, BorderLayout.EAST);
      head.setPreferredSize(new Dimension(10, 32));
      add(head);

      var allowed = 0;
      var declined = 0;
      if (scope != null) {
        for (var activity : Policy.ACTIVITIES) {
          var rule = scope.rules().stream().filter(r -> r.activities().contains(activity)).findFirst().orElse(null);
          if (rule == null) continue;
          if (rule.effect().equals("allow")) allowed++;
          else if (rule.effect().equals("deny")) declined++;
        }
      }
      add(new Item("shield", "Policy", new String[] {allowed + " allowed", declined + " declined"}, new Color[] {Theme.p.allow, Theme.p.deny}, () -> {
        open = !open;
        update();
      }));
      if (open && scope != null) {
        var rules = new Ui.Stack(0);
        rules.setBorder(Ui.pad(0, 0, 4, 0));
        for (var activity : Policy.ACTIVITIES) {
          var rule = scope.rules().stream().filter(r -> r.activities().contains(activity)).findFirst().orElse(null);
          if (rule == null) continue;
          var effect = rule.effect().equals("allow") ? (rule.requireAttempt() ? "With attempt" : "Allowed") : rule.effect().equals("deny") ? "Declined" : "Ask instructor";
          rules.add(new Line(Ui.ACTIVITY.getOrDefault(activity, activity), effect, rule.requireAttempt() && rule.effect().equals("allow") ? Theme.p.clarify : Theme.effect(rule.effect())));
        }
        add(rules);
      }
      var checkOnly = state.settings.answer.equals(Models.ADMISSION_ONLY);
      add(new Item("spark", "Answers", new String[] {checkOnly ? "Check only" : Models.label(state.settings.answer)}, new Color[] {Theme.p.muted}, AskView.this::answerMenu));
      add(new Item("eye", "Checks", new String[] {Models.label(state.settings.classifier)}, new Color[] {Theme.p.muted}, AskView.this::classifierMenu));
      var custom = false;
      var sha = "";
      try {
        var store = PromptStore.open();
        custom = store.custom(PromptStore.Kind.CLASSIFIER) || store.custom(PromptStore.Kind.SYSTEM);
        sha = Ui.shortSha(Course.hash(store.text(PromptStore.Kind.SYSTEM)));
      } catch (Exception ignored) {
        // Prompt provenance is informational; the Admin view reports read failures.
      }
      add(new Item("edit", "Prompts", new String[] {custom ? "Custom" : "Default", sha}, new Color[] {custom ? Theme.unc() : Theme.p.muted, Theme.p.faint}, state.student ? null : () -> frame.show("admin")));
      revalidate();
      repaint();
      if (getParent() != null) getParent().doLayout();
    }

    /** A 30px card row: icon, label, then right-aligned values in their own colors. */
    private final class Item extends JComponent {
      private final String icon;
      private final String text;
      private final String[] values;
      private final Color[] tones;
      private boolean hover;

      Item(String icon, String text, String[] values, Color[] tones, Runnable action) {
        this.icon = icon;
        this.text = text;
        this.values = values;
        this.tones = tones;
        if (action != null) {
          Ui.hover(this, () -> { hover = true; repaint(); }, () -> { hover = false; repaint(); });
          Ui.click(this, action);
        }
      }

      @Override
      public Dimension getPreferredSize() {
        return new Dimension(100, 30);
      }

      @Override
      protected void paintComponent(Graphics graphics) {
        var g = Ui.smooth(graphics);
        if (hover) {
          g.setColor(Theme.p.hover);
          g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
        }
        Ui.icon(icon, 15, Theme.p.muted).paintIcon(this, g, 12, (getHeight() - 15) / 2);
        g.setFont(Theme.ui(14f));
        var metrics = g.getFontMetrics();
        g.setColor(Theme.p.text);
        g.drawString(text, 39, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
        g.setFont(Theme.ui(13.5f));
        var small = g.getFontMetrics();
        var x = getWidth() - 12;
        for (int i = values.length - 1; i >= 0; i--) {
          if (values[i].isEmpty()) continue;
          x -= small.stringWidth(values[i]);
          g.setColor(tones[i]);
          g.drawString(values[i], x, (getHeight() - small.getHeight()) / 2 + small.getAscent());
          x -= 6;
        }
        g.dispose();
      }
    }

    /** An expanded policy line: the kind of help and what the policy does with it. */
    private static final class Line extends JComponent {
      private final String text;
      private final String effect;
      private final Color tone;

      Line(String text, String effect, Color tone) {
        this.text = text;
        this.effect = effect;
        this.tone = tone;
      }

      @Override
      public Dimension getPreferredSize() {
        return new Dimension(100, 25);
      }

      @Override
      protected void paintComponent(Graphics graphics) {
        var g = Ui.smooth(graphics);
        g.setFont(Theme.ui(13f));
        var metrics = g.getFontMetrics();
        var y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
        g.setColor(tone);
        g.fillOval(41, getHeight() / 2 - 2, 5, 5);
        g.setColor(Theme.p.muted);
        g.drawString(text, 53, y);
        g.setColor(tone);
        g.drawString(effect, getWidth() - 12 - metrics.stringWidth(effect), y);
        g.dispose();
      }
    }
  }
}

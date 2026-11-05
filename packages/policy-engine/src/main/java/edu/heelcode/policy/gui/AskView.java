package edu.heelcode.policy.gui;

import edu.heelcode.policy.Bridge;
import edu.heelcode.policy.Course;
import edu.heelcode.policy.Fixtures;
import edu.heelcode.policy.Models;
import edu.heelcode.policy.Policy;
import edu.heelcode.policy.PromptStore;
import edu.heelcode.policy.Threads;
import edu.heelcode.policy.Workspace;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The student view. Every request is classified and checked against the active assignment
 * policy before any answer model is called; declined and unclear requests get a teaching
 * response with the permitted next steps instead of an answer.
 */
final class AskView extends JPanel implements MainFrame.Refreshable {
  private static final int COLUMN = 780;
  private final MainFrame frame;
  private final State state;
  private final ExecutorService serial =
      Executors.newSingleThreadExecutor(task -> {
        var thread = new Thread(task, "heelcode-policy-chat");
        thread.setDaemon(true);
        return thread;
      });
  private final JLabel title = Ui.label("New thread", 14f, Font.BOLD, Theme.p.text);
  private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
  private final CardLayout cards = new CardLayout();
  private final JPanel center = new JPanel(cards);
  private final Ui.Stack transcript = new Ui.Stack(14);
  private final JScrollPane scroll = Ui.scroll(transcript);
  private final Ui.Stack hero = new Ui.Stack(0);
  private final Ui.Stack setup = new Ui.Stack(0);
  private final Composer composer = new Composer();
  private final JLabel footnote = Ui.label("", 11.5f, Font.PLAIN, Theme.p.faint);
  private final JPanel bottom = new JPanel(new BorderLayout());
  private final Inspector inspector = new Inspector();
  private boolean busy;
  private String assignment = "";
  private String rendered;
  private String policyDigest = "";

  AskView(MainFrame frame) {
    super(new BorderLayout());
    this.frame = frame;
    this.state = frame.state;
    setOpaque(false);

    var header = new JPanel(new BorderLayout());
    header.setOpaque(false);
    header.setBorder(Ui.pad(16, 22, 12, 14));
    var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    left.setOpaque(false);
    left.add(title);
    chips.setOpaque(false);
    left.add(chips);
    header.add(left, BorderLayout.WEST);
    var toggle = Ui.ghost("", "panel");
    toggle.setToolTipText("Toggle inspector (⌘I)");
    toggle.addActionListener(event -> toggleInspector());
    header.add(toggle, BorderLayout.EAST);
    var main = new JPanel(new BorderLayout());
    main.setOpaque(false);
    main.add(header, BorderLayout.NORTH);

    transcript.maxWidth = COLUMN;
    transcript.setBorder(Ui.pad(18, 24, 28, 24));
    center.setOpaque(false);
    center.add(backdrop(hero), "empty");
    center.add(scroll, "thread");
    center.add(backdrop(setup), "setup");
    main.add(center, BorderLayout.CENTER);

    bottom.setOpaque(false);
    bottom.setBorder(Ui.pad(4, 24, 14, 24));
    var composerColumn = new Ui.Stack(8);
    composerColumn.maxWidth = COLUMN;
    composerColumn.add(composer);
    footnote.setHorizontalAlignment(JLabel.CENTER);
    composerColumn.add(footnote);
    bottom.add(composerColumn, BorderLayout.CENTER);
    main.add(bottom, BorderLayout.SOUTH);
    add(main, BorderLayout.CENTER);
    add(inspector, BorderLayout.EAST);
    inspector.setVisible(state.settings.inspector);

    composer.onSend = this::send;
    composer.assignment.addActionListener(event -> assignmentMenu());
    composer.classifier.addActionListener(event -> classifierMenu());
    composer.answer.addActionListener(event -> answerMenu());
    state.listen(this::sync);
    sync();
  }

  @Override
  public void refresh() {
    sync();
  }

  void toggleInspector() {
    state.settings.inspector = !inspector.isVisible();
    state.settings.save();
    inspector.setVisible(state.settings.inspector);
    revalidate();
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
    chips.removeAll();
    if (active != null) {
      chips.add(Ui.Chip.neutral(state.course()));
      chips.add(Ui.Chip.of("Policy active · " + Ui.shortSha(active.policyDigest()), Theme.p.allow));
    }
    chips.revalidate();
    if (active == null) {
      title.setText("HeelCode");
      buildSetup();
      cards.show(center, "setup");
      bottom.setVisible(false);
      rendered = null;
      inspector.update();
      return;
    }
    bottom.setVisible(true);
    if (!state.thread.isEmpty() && !state.thread.equals(rendered)) load(state.thread);
    if (state.thread.isEmpty() && !"".equals(rendered)) {
      rendered = "";
      transcript.removeAll();
      title.setText("New thread");
      buildHero();
      cards.show(center, "empty");
    }
    updateControls();
    inspector.update();
  }

  private void load(String id) {
    try {
      var thread = Threads.load(state.workspace, id);
      rendered = id;
      assignment = thread.summary().assignment();
      state.settings.answer = thread.summary().model();
      title.setText(thread.summary().title());
      transcript.removeAll();
      for (var entry : thread.entries()) {
        transcript.add(new Bubble(entry.prompt()), Ui.Align.RIGHT);
        var admission =
            new Workspace.Response(
                entry.requestId(),
                id,
                0,
                state.active.course().courseId(),
                state.active.policyDigest(),
                entry.features(),
                entry.decision());
        addAdmission(admission, "", -1);
        if (entry.status().equals("completed")) transcript.add(new Answer(entry.text(), Models.label(thread.summary().model()), -1));
        else if (!List.of("blocked", "admission").contains(entry.status()))
          transcript.add(new Notice("No answer was saved for this turn (" + entry.status() + ")", entry.error(), Theme.p.clarify));
      }
      cards.show(center, "thread");
      transcript.revalidate();
      scrollToBottom();
    } catch (Exception error) {
      frame.toast("Could not open thread: " + Ui.message(error), Theme.p.deny);
      state.thread = "";
    }
  }

  private void updateControls() {
    var scope = state.active == null ? null : state.active.policy().scope(assignment);
    composer.assignment.setText(scope == null ? "Assignment" : label(scope));
    composer.classifier.setText(Models.label(state.settings.classifier) + " check");
    composer.answer.setText(state.settings.answer.equals(Models.ADMISSION_ONLY) ? "Check only" : Models.label(state.settings.answer) + " answers");
    var who = Models.label(state.settings.classifier);
    var policy = state.course() + " · " + (assignment.equals("*") ? "course" : assignment) + " policy";
    footnote.setText(
        state.settings.answer.equals(Models.ADMISSION_ONLY)
            ? "Checked by " + who + " against the " + policy + ". Check-only mode: no answer model is called."
            : "Checked by " + who + " against the " + policy + " before anything reaches the answer model.");
  }

  private static String defaultAssignment(Policy policy) {
    return policy.scopes().stream().map(Policy.Scope::id).filter(id -> !id.equals("*")).findFirst().orElse("*");
  }

  private static String label(Policy.Scope scope) {
    if (scope.id().equals("*")) return "General course question";
    var name = scope.title().replaceFirst("(?i)^assignment\\s+" + java.util.regex.Pattern.quote(scope.id()) + "\\s*[:—-]\\s*", "");
    return scope.id() + " · " + (name.length() > 28 ? name.substring(0, 27) + "…" : name);
  }

  // ------------------------------------------------------------------ menus

  private void assignmentMenu() {
    if (state.active == null) return;
    var menu = new JPopupMenu();
    for (var scope : state.active.policy().scopes()) {
      var item = new JMenuItem((scope.id().equals("*") ? "General course question" : scope.id() + " — " + scope.title()), scope.id().equals(assignment) ? Ui.icon("check", 14, Theme.p.accent) : null);
      item.addActionListener(event -> {
        if (scope.id().equals(assignment)) return;
        assignment = scope.id();
        restartIfNeeded("Assignment changed to " + scope.id() + " — started a new thread");
      });
      menu.add(item);
    }
    menu.show(composer.assignment, 0, -menu.getPreferredSize().height - 6);
  }

  private void classifierMenu() {
    var menu = new JPopupMenu();
    for (var choice : Models.CLASSIFIERS) {
      var reason = Models.unavailable(choice.id());
      var item = new JMenuItem("<html>" + choice.label() + "<br><span style='font-size:9px;color:" + Theme.hex(Theme.p.faint) + "'>" + (reason == null ? choice.detail() : reason) + "</span></html>", choice.id().equals(state.settings.classifier) ? Ui.icon("check", 14, Theme.p.accent) : null);
      item.setEnabled(reason == null);
      item.addActionListener(event -> {
        state.settings.classifier = choice.id();
        state.settings.save();
        updateControls();
        inspector.update();
      });
      menu.add(item);
    }
    if (!Models.CLASSIFIERS.isEmpty() && Models.unavailable(Models.JEV) != null) {
      menu.addSeparator();
      var key = new JMenuItem("Use a TypeSafe key for this session…", Ui.icon("key", 14, Theme.p.muted));
      key.addActionListener(event -> sessionKey());
      menu.add(key);
    }
    menu.show(composer.classifier, 0, -menu.getPreferredSize().height - 6);
  }

  private void answerMenu() {
    var menu = new JPopupMenu();
    for (var choice : Models.ANSWERS) {
      var item = new JMenuItem("<html>" + choice.label() + "<br><span style='font-size:9px;color:" + Theme.hex(Theme.p.faint) + "'>" + choice.detail() + "</span></html>", choice.id().equals(state.settings.answer) ? Ui.icon("check", 14, Theme.p.accent) : null);
      item.addActionListener(event -> {
        if (choice.id().equals(state.settings.answer)) return;
        state.settings.answer = choice.id();
        state.settings.save();
        restartIfNeeded("Answer mode changed — started a new thread");
      });
      menu.add(item);
    }
    menu.show(composer.answer, 0, -menu.getPreferredSize().height - 6);
  }

  private void sessionKey() {
    var field = new javax.swing.JPasswordField(36);
    var result = javax.swing.JOptionPane.showConfirmDialog(frame, new Object[] {"TypeSafe API key (kept in memory for this session only):", field}, "Jev classifier", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE, Ui.icon("key", 28, Theme.p.accent));
    if (result != javax.swing.JOptionPane.OK_OPTION || field.getPassword().length == 0) return;
    System.setProperty("heelcode.typesafe.key", new String(field.getPassword()));
    state.settings.classifier = Models.JEV;
    state.settings.save();
    updateControls();
    frame.toast("Jev is available for this session", Theme.p.allow);
  }

  /** Sessions are pinned to one assignment and answer model, so a change starts a new thread. */
  private void restartIfNeeded(String message) {
    if (!state.thread.isEmpty()) {
      state.select("");
      frame.toast(message, Theme.p.accent);
    }
    updateControls();
    inspector.update();
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
    if (transcript.getComponentCount() == 0) title.setText(text.length() > 60 ? text.substring(0, 59) + "…" : text);
    cards.show(center, "thread");
    rendered = state.thread;
    transcript.add(new Bubble(text), Ui.Align.RIGHT);
    var classifierLabel = Models.label(state.settings.classifier);
    var checking = new Working("Checking the " + assignment + " policy with " + classifierLabel);
    transcript.add(checking);
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
                    transcript.add(answering[0]);
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
            case "completed" -> transcript.add(new Answer(response.text(), Models.label(answerModel), answerMillis));
            case "blocked" -> {}
            default -> transcript.add(new Notice(failureTitle(response.status()), response.error(), response.status().equals("busy") ? Theme.p.clarify : Theme.p.deny));
          }
          finish(response.sessionId());
        });
      } catch (Exception error) {
        SwingUtilities.invokeLater(() -> {
          transcript.remove(checking);
          transcript.add(new Notice("Request not sent — nothing was forwarded to an answer model", Ui.message(error), Theme.p.deny));
          finish(state.thread);
        });
      }
    });
  }

  private static String failureTitle(String status) {
    return switch (status) {
      case "failed" -> "The answer model failed — the policy decision is saved";
      case "uncertain" -> "Answer outcome unknown — it will not be retried automatically";
      case "busy" -> "This thread is busy — retry in a moment";
      default -> "The request could not continue";
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

  // ------------------------------------------------------------------ transcript pieces

  private void addAdmission(Workspace.Response admission, String classifier, long millis) {
    transcript.add(new Check(admission, classifier, millis));
    if (!admission.decision().forward()) transcript.add(new Gate(admission));
  }

  /** The student's message: a rounded bubble, right-aligned like Codex. */
  private static final class Bubble extends Ui.Stack implements Ui.Sized {
    private final javax.swing.JTextArea text;

    Bubble(String message) {
      super(0);
      card(Theme.p.bubble, null);
      arc = 18;
      setBorder(Ui.pad(10, 14, 10, 14));
      text = Ui.selectable(message, Theme.ui(13.5f), Theme.p.text);
      add(text);
    }

    @Override
    public int widthFor(int available) {
      var metrics = text.getFontMetrics(text.getFont());
      var widest = 0;
      for (var line : text.getText().split("\n")) widest = Math.max(widest, metrics.stringWidth(line));
      return Math.min((int) (available * 0.78), widest + 30);
    }
  }

  /** Compact, expandable admission row (Codex's tool-call row style). */
  private final class Check extends Ui.Stack {
    private final Ui.Stack details = new Ui.Stack(8);
    private boolean open;

    Check(Workspace.Response admission, String classifier, long millis) {
      super(6);
      var decision = admission.decision();
      var tone = Theme.effect(decision.action());
      var row = new JPanel(new BorderLayout(8, 0));
      row.setOpaque(false);
      var icon = switch (decision.action()) {
        case "allow" -> "check";
        case "deny" -> "ban";
        default -> "help";
      };
      var head = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
      head.setOpaque(false);
      head.add(Ui.iconLabel(icon, 15, tone));
      var verdict = switch (decision.action()) {
        case "allow" -> "Policy check passed";
        case "deny" -> "Policy check declined this request";
        default -> "Policy check needs more from you";
      };
      head.add(Ui.label(verdict, 12.5f, Font.BOLD, Theme.p.muted));
      var detected = admission.features().activities();
      if (!detected.isEmpty()) head.add(Ui.label("· " + Ui.join(detected), 12.5f, Font.PLAIN, Theme.p.faint));
      row.add(head, BorderLayout.WEST);
      var meta = new ArrayList<String>();
      if (!classifier.isEmpty()) meta.add(classifier);
      if (millis >= 0) meta.add(Ui.seconds(millis));
      var chevron = Ui.iconLabel("chevron-down", 14, Theme.p.faint);
      var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
      right.setOpaque(false);
      if (!meta.isEmpty()) right.add(Ui.label(String.join(" · ", meta), 11.5f, Font.PLAIN, Theme.p.faint));
      right.add(chevron);
      row.add(right, BorderLayout.EAST);
      row.setPreferredSize(new Dimension(10, 22));
      add(row);

      details.setBorder(Ui.pad(10, 12, 10, 12));
      details.card(Theme.p.dark ? Theme.p.surface : Theme.p.raised, Theme.p.border);
      details.arc = 12;
      var features = admission.features();
      var flags = new Ui.Wrap(6, 6);
      for (var activity : features.activities()) flags.add(Ui.Chip.of(Ui.ACTIVITY.getOrDefault(activity, activity), tone));
      flags.add(Ui.Chip.neutral(features.hasAttempt() ? "attempt shown" : "no attempt shown"));
      if (features.unclear()) flags.add(Ui.Chip.of("unclear", Theme.p.clarify));
      if (features.dishonest()) flags.add(Ui.Chip.of("concealment / misrepresentation", Theme.p.deny));
      details.add(flags);
      details.add(Ui.wrap("Classifier: " + features.rationale(), 12.5f, Theme.p.muted));
      details.add(Ui.wrap("Controller: " + decision.reason().replace('_', ' ') + " → " + decision.action() + (decision.forward() ? " (forwarded)" : " (not forwarded)"), 12.5f, Theme.p.muted));
      var trace = "Request " + admission.requestId() + (admission.turn() > 0 ? " · turn " + admission.turn() : "") + " · policy " + Ui.shortSha(admission.policyDigest());
      details.add(Ui.label(trace, 11f, Font.PLAIN, Theme.p.faint));
      details.setVisible(false);
      add(details);
      Ui.click(row, () -> {
        open = !open;
        details.setVisible(open);
        chevron.setIcon(Ui.icon(open ? "chevron-down" : "chevron-right", 14, Theme.p.faint));
        transcript.revalidate();
        transcript.repaint();
      });
      chevron.setIcon(Ui.icon("chevron-right", 14, Theme.p.faint));
    }
  }

  /** The teaching response for a blocked request: the boundary, why, and what is permitted. */
  private final class Gate extends Ui.Stack {
    Gate(Workspace.Response admission) {
      super(10);
      var decision = admission.decision();
      var tone = Theme.effect(decision.action());
      card(Theme.alpha(tone, Theme.p.dark ? 16 : 12), Theme.alpha(tone, Theme.p.dark ? 70 : 90));
      accent = tone;
      arc = 14;
      setBorder(Ui.pad(14, 20, 14, 16));
      // Live and saved turns both belong to the thread's pinned assignment.
      var scope = state.active.policy().scope(assignment);
      var heading = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
      heading.setOpaque(false);
      heading.add(Ui.iconLabel(decision.action().equals("deny") ? "ban" : "help", 17, tone));
      heading.add(Ui.label(headline(decision.reason(), scope), 14f, Font.BOLD, Theme.p.text));
      add(heading);
      add(Ui.wrap(decision.feedback(), 13.5f, Theme.mix(Theme.p.text, Theme.p.muted, 0.25)));
      if (scope != null) {
        var permitted = scope.rules().stream().filter(rule -> rule.effect().equals("allow")).toList();
        if (!permitted.isEmpty()) {
          add(Ui.gap(Ui.section("You can ask for"), 6));
          var options = new Ui.Wrap(6, 6);
          for (var rule : permitted) {
            for (var activity : rule.activities()) {
              var chip = new Ui.Chip(Ui.ACTIVITY.getOrDefault(activity, activity) + (rule.requireAttempt() ? " · share your attempt" : ""), Theme.p.text, Theme.p.dark ? Theme.p.raised : Theme.p.surface, Ui.icon("plus", 11, Theme.p.accent));
              chip.setToolTipText("Start a request of this kind");
              Ui.click(chip, () -> composer.prefill(template(activity)));
              options.add(chip);
            }
          }
          add(options);
        }
      }
      for (var evidence : decision.evidence().stream().limit(2).toList()) {
        var quote = evidence.quote().replaceAll("\\s+", " ").strip();
        add(new Ui.IconText("quote", (quote.length() > 220 ? quote.substring(0, 219) + "…" : quote) + "  — " + evidence.source(), 12f, Theme.p.faint));
      }
    }

  }

  private static String headline(String reason, Policy.Scope scope) {
    var name = scope == null ? "this assignment" : scope.id().equals("*") ? "this course" : scope.id();
    return switch (reason) {
      case "deception" -> "I can't help hide AI use or misrepresent authorship";
      case "policy_prohibits_activity" -> "That's outside " + name + "'s AI policy";
      case "attempt_required" -> "Share your attempt first";
      case "policy_uncertain" -> "Your instructor hasn't settled this one";
      case "unknown_assignment" -> "Choose an assignment in this course";
      default -> "Tell me a bit more about what you need";
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

  /** A model answer rendered from Markdown, with a quiet provenance line. */
  private static final class Answer extends Ui.Stack {
    Answer(String markdown, String model, long millis) {
      super(6);
      add(Markdown.pane(markdown));
      var meta = model + (millis >= 0 ? " · " + Ui.seconds(millis) : "") + " · answered within the admitted policy";
      var line = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
      line.setOpaque(false);
      line.add(Brand.mark(12));
      line.add(Ui.label(meta, 11.5f, Font.PLAIN, Theme.p.faint));
      line.setPreferredSize(new Dimension(10, 16));
      add(line);
    }
  }

  private static final class Notice extends Ui.Stack {
    Notice(String title, String detail, Color tone) {
      super(6);
      card(Theme.alpha(tone, 14), Theme.alpha(tone, 80));
      setBorder(Ui.pad(10, 14, 10, 14));
      add(Ui.wrap(title, 13f, Theme.p.text));
      if (detail != null && !detail.isBlank()) add(Ui.wrap(detail, 12f, Theme.p.muted));
    }
  }

  /** Spinner row with live elapsed time while the classifier or answer model works. */
  private static final class Working extends JPanel {
    final JLabel label;
    private final JLabel clock = Ui.label("0.0s", 11.5f, Font.PLAIN, Theme.p.faint);
    private final Timer timer;

    Working(String text) {
      super(new FlowLayout(FlowLayout.LEFT, 8, 0));
      setOpaque(false);
      add(new Ui.Spinner(14));
      label = Ui.label(text, 12.5f, Font.PLAIN, Theme.p.muted);
      add(label);
      add(clock);
      var started = System.nanoTime();
      timer = new Timer(100, event -> clock.setText(Ui.seconds((System.nanoTime() - started) / 1_000_000)));
      setPreferredSize(new Dimension(10, 22));
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

  // ------------------------------------------------------------------ empty and setup states

  private JComponent backdrop(Ui.Stack content) {
    var panel =
        new JPanel(new BorderLayout()) {
          @Override
          protected void paintComponent(Graphics graphics) {
            var g = Ui.smooth(graphics);
            Brand.argyle(g, getWidth(), getHeight(), (int) (getHeight() * 0.42));
            g.dispose();
          }
        };
    panel.setOpaque(false);
    var holder = new JPanel(new java.awt.GridBagLayout());
    holder.setOpaque(false);
    holder.setBorder(Ui.pad(0, 28, 0, 28));
    content.maxWidth = 700;
    holder.add(content);
    panel.add(holder, BorderLayout.CENTER);
    return panel;
  }

  private void buildHero() {
    hero.removeAll();
    hero.gap = 0;
    hero.add(Ui.gap(centerOf(Brand.mark(52)), 18));
    var heading = Ui.label("What are we working on?", 26f, Font.BOLD, Theme.p.text);
    heading.setHorizontalAlignment(JLabel.CENTER);
    hero.add(Ui.gap(heading, 8));
    var scope = state.active.policy().scope(assignment);
    var subtitle = Ui.label(state.course() + (scope == null ? "" : " · " + scope.title()), 13.5f, Font.PLAIN, Theme.p.muted);
    subtitle.setHorizontalAlignment(JLabel.CENTER);
    hero.add(Ui.gap(subtitle, 4));
    var note = Ui.centered("Every request is checked against your instructor's policy before an answer model sees it.", 12.5f, Theme.p.faint);
    hero.add(Ui.gap(note, 26));
    var texts = new ArrayList<javax.swing.JTextArea>();
    var grid = new Cards(texts);
    for (var suggestion : suggestions()) grid.add(suggestionCard(suggestion[0], suggestion[1], texts));
    hero.add(grid);
    hero.revalidate();
    hero.repaint();
  }

  private List<String[]> suggestions() {
    if (state.active != null && state.active.course().courseId().equals("sys301") && assignment.equals("a1"))
      return List.of(
          new String[] {"chat", "Explain how fork differs from exec."},
          new String[] {"terminal", "My pipeline hangs. I think the parent keeps the write end open, so EOF never arrives. How can I confirm that?"},
          new String[] {"file", "Write the whole mini-shell for me so I can submit it."});
    return List.of(
        new String[] {"chat", "Explain the key idea this assignment is practicing."},
        new String[] {"terminal", "Here's the error I'm getting and what I already tried: "},
        new String[] {"file", "Write the complete solution for me."});
  }

  /** Three equal cards whose height follows the longest suggestion at the current width. */
  private static final class Cards extends JPanel implements Ui.Flow {
    private final List<javax.swing.JTextArea> texts;

    Cards(List<javax.swing.JTextArea> texts) {
      super(new GridLayout(1, 3, 10, 0));
      this.texts = texts;
      setOpaque(false);
    }

    @Override
    public int heightFor(int width) {
      var inner = (width - 20) / 3 - 28;
      var tallest = 0;
      for (var text : texts) tallest = Math.max(tallest, Ui.heightFor(text, Math.max(60, inner)));
      return 12 + 16 + 8 + tallest + 14;
    }
  }

  private JComponent suggestionCard(String icon, String text, List<javax.swing.JTextArea> texts) {
    var card = new Ui.Card(Theme.p.dark ? Theme.alpha(Theme.p.surface, 230) : Theme.p.surface, Theme.p.border);
    card.setLayout(new BorderLayout(0, 8));
    card.setBorder(Ui.pad(12, 14, 12, 14));
    card.add(Ui.iconLabel(icon, 16, Theme.p.accent), BorderLayout.NORTH);
    ((JLabel) card.getComponent(0)).setHorizontalAlignment(JLabel.LEFT);
    var body = Ui.wrap(text, 12.5f, Theme.p.muted);
    texts.add(body);
    card.add(body, BorderLayout.CENTER);
    Ui.hover(card, () -> { card.fill = Theme.p.hover; card.repaint(); }, () -> { card.fill = Theme.p.dark ? Theme.alpha(Theme.p.surface, 230) : Theme.p.surface; card.repaint(); });
    Ui.click(card, () -> {
      if (text.endsWith(": ")) composer.prefill(text);
      else send(text);
    });
    for (var child : card.getComponents()) Ui.click((JComponent) child, () -> {
      if (text.endsWith(": ")) composer.prefill(text);
      else send(text);
    });
    return card;
  }

  private void buildSetup() {
    setup.removeAll();
    setup.add(Ui.gap(centerOf(Brand.mark(52)), 18));
    var heading = Ui.label(state.workspace == null ? "Open a course workspace" : "This workspace isn't ready", 24f, Font.BOLD, Theme.p.text);
    heading.setHorizontalAlignment(JLabel.CENTER);
    setup.add(Ui.gap(heading, 10));
    var explain = Ui.centered("HeelCode answers inside one course at a time. A workspace holds the instructor's activated policy and your policy-checked threads; nothing reaches an answer model until the policy admits it.", 13.5f, Theme.p.muted);
    setup.add(Ui.gap(explain, 18));
    if (!state.problem.isEmpty()) {
      var problem = new Notice("Policy unavailable", state.problem, Theme.p.clarify);
      setup.add(Ui.gap(problem, 18));
    }
    var buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
    buttons.setOpaque(false);
    if (State.demoAvailable()) {
      var demo = Ui.primary("Open SYS 301 demo", "spark");
      demo.addActionListener(event -> frame.createDemo());
      buttons.add(demo);
    }
    var open = Ui.secondary("Open workspace…", "folder");
    open.addActionListener(event -> frame.openWorkspace());
    buttons.add(open);
    if (!state.student) {
      var generate = Ui.secondary("Generate a policy", "shield");
      generate.addActionListener(event -> frame.show("policies"));
      buttons.add(generate);
    }
    buttons.setPreferredSize(new Dimension(560, 38));
    setup.add(buttons);
    setup.maxWidth = 600;
    setup.revalidate();
    setup.repaint();
  }

  private static JComponent centerOf(JComponent component) {
    var panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
    panel.setOpaque(false);
    panel.add(component);
    panel.setPreferredSize(new Dimension(10, component.getPreferredSize().height));
    return panel;
  }

  // ------------------------------------------------------------------ inspector

  /** Right-hand context: what this assignment permits, the thread, and prompt provenance. */
  private final class Inspector extends JPanel {
    private final Ui.Stack body = new Ui.Stack(6);

    Inspector() {
      super(new BorderLayout());
      setPreferredSize(new Dimension(300, 100));
      body.setBorder(Ui.pad(16, 16, 16, 16));
      add(Ui.scroll(body), BorderLayout.CENTER);
    }

    @Override
    protected void paintComponent(Graphics g) {
      g.setColor(Theme.p.dark ? Theme.p.sidebar : Theme.p.raised);
      g.fillRect(0, 0, getWidth(), getHeight());
      g.setColor(Theme.p.border);
      g.fillRect(0, 0, 1, getHeight());
    }

    void update() {
      body.removeAll();
      var active = state.active;
      if (active == null) {
        body.add(Ui.section("Inspector"));
        body.add(Ui.wrap("Open a workspace to see its policy.", 12.5f, Theme.p.faint));
        body.revalidate();
        return;
      }
      var scope = active.policy().scope(assignment);
      body.add(Ui.gap(Ui.section("Assignment policy"), 8));
      if (scope != null) {
        body.add(Ui.gap(Ui.wrap(scope.title(), 13f, Theme.p.text), 8));
        for (var activity : Policy.ACTIVITIES) {
          var rule = scope.rules().stream().filter(r -> r.activities().contains(activity)).findFirst().orElse(null);
          if (rule == null) continue;
          var row = new JPanel(new BorderLayout());
          row.setOpaque(false);
          row.add(Ui.label(Ui.ACTIVITY.get(activity), 12.5f, Font.PLAIN, Theme.p.muted), BorderLayout.WEST);
          var effect = rule.effect().equals("allow") ? (rule.requireAttempt() ? "with attempt" : "allowed") : rule.effect().equals("deny") ? "declined" : "ask instructor";
          row.add(Ui.Chip.of(effect, Theme.effect(rule.effect())), BorderLayout.EAST);
          row.setPreferredSize(new Dimension(10, 24));
          body.add(row);
        }
      }
      body.add(Ui.gap(Ui.divider(), 4));
      body.add(Ui.gap(Ui.section("This thread"), 8));
      body.add(meta("Session", state.thread.isEmpty() ? "new — created on first request" : state.thread.substring(0, 12) + "…"));
      body.add(meta("Classifier", Models.label(state.settings.classifier)));
      body.add(meta("Answers", state.settings.answer.equals(Models.ADMISSION_ONLY) ? "none (check only)" : Models.label(state.settings.answer)));
      body.add(Ui.gap(Ui.divider(), 4));
      body.add(Ui.gap(Ui.section("Prompts"), 8));
      try {
        var store = PromptStore.open();
        for (var kind : PromptStore.Kind.values())
          body.add(meta(kind == PromptStore.Kind.CLASSIFIER ? "Classifier" : "System", (store.custom(kind) ? "custom · " : "default · ") + Ui.shortSha(Course.hash(store.text(kind)))));
      } catch (Exception error) {
        body.add(Ui.wrap(Ui.message(error), 12f, Theme.p.deny));
      }
      if (!state.student) {
        var edit = Ui.ghost("Edit prompts in Admin", "sliders");
        edit.addActionListener(event -> frame.show("admin"));
        body.add(Ui.row(0, edit));
      }
      body.add(Ui.gap(Ui.divider(), 4));
      body.add(Ui.gap(Ui.section("Workspace"), 8));
      body.add(meta("Course", active.course().courseId() + " · " + active.course().sources().size() + " sources"));
      body.add(meta("Policy", Ui.shortSha(active.policyDigest())));
      body.add(meta("Sources", Ui.shortSha(active.course().digest())));
      body.add(Ui.wrap(active.policy().version(), 11.5f, Theme.p.faint));
      body.revalidate();
      body.repaint();
    }

    private JComponent meta(String key, String value) {
      var row = new JPanel(new BorderLayout(10, 0));
      row.setOpaque(false);
      row.add(Ui.label(key, 12f, Font.PLAIN, Theme.p.faint), BorderLayout.WEST);
      var label = Ui.label(value, 12f, Font.PLAIN, Theme.p.muted);
      label.setHorizontalAlignment(JLabel.RIGHT);
      row.add(label, BorderLayout.CENTER);
      row.setPreferredSize(new Dimension(10, 20));
      return row;
    }
  }
}

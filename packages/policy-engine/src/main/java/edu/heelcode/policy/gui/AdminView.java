package edu.heelcode.policy.gui;

import edu.heelcode.policy.Bridge;
import edu.heelcode.policy.Classifier;
import edu.heelcode.policy.Course;
import edu.heelcode.policy.Models;
import edu.heelcode.policy.Policy;
import edu.heelcode.policy.PromptStore;
import edu.heelcode.policy.Prompts;
import edu.heelcode.policy.Workspace;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Admin prompt editor. Two prompts only: the classifier's extraction instructions and the answer
 * model's system prompt. Saves are versioned by SHA-256; defaults reproduce the frozen research
 * prompts. Editing a prompt never changes the deterministic allow/deny/clarify controller.
 */
final class AdminView extends JPanel implements MainFrame.Refreshable {
  private static final DateTimeFormatter WHEN =
      DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
  private final MainFrame frame;
  private final PromptStore store = PromptStore.open();
  private PromptStore.Kind kind = PromptStore.Kind.CLASSIFIER;
  private final Ui.Stack list = new Ui.Stack(4);
  private final JLabel title = Ui.label("", 19f, Font.BOLD, Theme.p.text);
  private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
  private final JTextArea description = Ui.wrap("", 12.5f, Theme.p.muted);
  private final JTextArea editor = new JTextArea();
  private final JLabel counts = Ui.label("", 11.5f, Font.PLAIN, Theme.p.faint);
  private final Ui.Stack warnings = new Ui.Stack(3);
  private final JTextField note = new JTextField();
  private final JButton save = Ui.primary("Save revision", "check");
  private final JButton revert = Ui.secondary("Revert", "undo");
  private final JButton restore = Ui.ghost("Restore default", "refresh");
  private final JButton test = Ui.secondary("Test draft", "play");
  private final JPanel lower = new JPanel(new BorderLayout());
  private final JSplitPane split;
  private String saved = "";
  private boolean loading;

  AdminView(MainFrame frame) {
    super(new BorderLayout());
    this.frame = frame;
    setOpaque(false);

    var side = new JPanel(new BorderLayout()) {
      @Override
      protected void paintComponent(Graphics g) {
        g.setColor(Theme.p.dark ? Theme.mix(Theme.p.bg, Theme.p.sidebar, 0.5) : Theme.p.raised);
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(Theme.p.border);
        g.fillRect(getWidth() - 1, 0, 1, getHeight());
      }
    };
    side.setPreferredSize(new Dimension(250, 100));
    list.setBorder(Ui.pad(54, 12, 12, 12));
    side.add(list, BorderLayout.NORTH);
    add(side, BorderLayout.WEST);

    var body = new JPanel(new BorderLayout());
    body.setOpaque(false);
    var head = new Ui.Stack(8);
    head.setBorder(Ui.pad(46, 28, 12, 28));
    var titleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    titleRow.setOpaque(false);
    titleRow.add(title);
    chips.setOpaque(false);
    titleRow.add(chips);
    titleRow.setPreferredSize(new Dimension(10, 30));
    head.add(titleRow);
    head.add(description);
    var tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    tools.setOpaque(false);
    var presets = Ui.pill("Presets", "book");
    presets.addActionListener(event -> presetsMenu(presets));
    var history = Ui.pill("History", "clock");
    history.addActionListener(event -> historyMenu(history));
    var preview = Ui.ghost("Preview assembled prompt", "eye");
    preview.addActionListener(event -> showPreview());
    test.addActionListener(event -> showTest());
    tools.add(presets);
    tools.add(history);
    tools.add(preview);
    tools.add(test);
    tools.setPreferredSize(new Dimension(10, 32));
    head.add(tools);
    body.add(head, BorderLayout.NORTH);

    editor.setFont(Theme.mono(12.5f));
    editor.setForeground(Theme.p.text);
    editor.setBackground(Theme.p.code);
    editor.setCaretColor(Theme.p.accent);
    editor.setLineWrap(true);
    editor.setWrapStyleWord(true);
    editor.setTabSize(2);
    editor.setBorder(Ui.pad(12, 8, 12, 14));
    editor.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent event) { edited(); }
      public void removeUpdate(DocumentEvent event) { edited(); }
      public void changedUpdate(DocumentEvent event) { edited(); }
    });
    var scroll = new JScrollPane(editor);
    scroll.setRowHeaderView(new Gutter(editor));
    scroll.setBorder(javax.swing.BorderFactory.createLineBorder(Theme.p.border));
    scroll.getViewport().setBackground(Theme.p.code);
    var editorHolder = new JPanel(new BorderLayout());
    editorHolder.setOpaque(false);
    editorHolder.setBorder(Ui.pad(0, 28, 0, 28));
    editorHolder.add(scroll, BorderLayout.CENTER);

    lower.setOpaque(false);
    lower.setBorder(Ui.pad(10, 28, 0, 28));
    split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorHolder, lower);
    split.setBorder(null);
    split.setOpaque(false);
    split.setResizeWeight(1.0);
    split.setDividerSize(8);
    split.setContinuousLayout(true);
    lower.setVisible(false);
    body.add(split, BorderLayout.CENTER);

    var foot = new JPanel(new BorderLayout(12, 8));
    foot.setOpaque(false);
    foot.setBorder(Ui.pad(10, 28, 18, 28));
    var status = new Ui.Stack(4);
    status.add(counts);
    status.add(warnings);
    foot.add(status, BorderLayout.NORTH);
    var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    actions.setPreferredSize(new Dimension(10, 36));
    actions.setOpaque(false);
    note.setColumns(18);
    note.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "Change note (optional)");
    note.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "arc: 10; margin: 4,8,4,8");
    actions.add(restore);
    actions.add(revert);
    actions.add(note);
    actions.add(save);
    foot.add(actions, BorderLayout.CENTER);
    body.add(foot, BorderLayout.SOUTH);
    add(body, BorderLayout.CENTER);

    save.addActionListener(event -> save());
    revert.addActionListener(event -> load());
    restore.addActionListener(event -> restore());
    load();
  }

  @Override
  public void refresh() {
    if (!dirty()) load();
    else buildList();
  }

  private void select(PromptStore.Kind next) {
    if (next == kind) return;
    if (dirty()) {
      var choice = JOptionPane.showConfirmDialog(frame, "Discard unsaved changes to the " + kind.title.toLowerCase() + "?", "Unsaved changes", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
      if (choice != JOptionPane.OK_OPTION) return;
    }
    kind = next;
    lower.setVisible(false);
    load();
  }

  private void load() {
    try {
      saved = store.text(kind);
      loading = true;
      editor.setText(saved);
      editor.setCaretPosition(0);
      loading = false;
      note.setText("");
      title.setText(kind.title);
      description.setText(kind == PromptStore.Kind.CLASSIFIER
          ? "Instructions the Luna/Terra classifier reads before each request's JSON. It only describes the request: activities, attempt evidence, ambiguity, and concealment. The output schema is enforced separately, and the Java controller — not this prompt — turns those features into allow, deny, or clarify. Jev uses its own typed questions."
          : "The answer model's system prompt for admitted requests. It replaces the agent's default coding-assistant prompt; the assignment's policy JSON and the student's request follow as the user message. It shapes how help is given, but it is not the gate: declined requests never reach the answer model.");
      test.setVisible(kind == PromptStore.Kind.CLASSIFIER);
      edited();
      buildList();
    } catch (Exception error) {
      frame.toast("Could not read prompts: " + Ui.message(error), Theme.p.deny);
    }
  }

  private boolean dirty() {
    try {
      return !PromptStore.normalize(editor.getText()).equals(saved);
    } catch (IllegalArgumentException empty) {
      return true;
    }
  }

  private void edited() {
    if (loading) return;
    var text = editor.getText();
    counts.setText(String.format("%,d characters · ≈%,d tokens · %,d lines · sha %s", text.length(), Math.round(text.length() / 4.0), editor.getLineCount(), shaOf(text)));
    warnings.removeAll();
    for (var warning : PromptStore.lint(kind, text)) warnings.add(new Ui.IconText("alert", warning, 12f, Theme.p.clarify));
    warnings.revalidate();
    var changed = dirty();
    save.setEnabled(changed);
    revert.setEnabled(changed);
    chips.removeAll();
    chips.add(changed ? Ui.Chip.of("Unsaved changes", Theme.p.clarify) : store.custom(kind) ? Ui.Chip.of("Custom", Theme.p.accent) : Ui.Chip.neutral("Packaged default"));
    chips.add(Ui.Chip.neutral(kind == PromptStore.Kind.CLASSIFIER ? "Applies to Luna · Terra" : "Applies to gated answers"));
    chips.revalidate();
    chips.repaint();
    revalidate();
  }

  private static String shaOf(String text) {
    try {
      return Ui.shortSha(Course.hash(PromptStore.normalize(text)));
    } catch (IllegalArgumentException empty) {
      return "—";
    }
  }

  private void save() {
    try {
      var revision = store.save(kind, editor.getText(), note.getText());
      frame.toast((revision.action().equals("default") ? "Matches the packaged default · " : "Saved " + kind.title.toLowerCase() + " · ") + Ui.shortSha(revision.sha256()), Theme.p.allow);
      load();
      frame.state.changed();
    } catch (Exception error) {
      frame.toast(Ui.message(error), Theme.p.deny);
    }
  }

  private void restore() {
    var choice = JOptionPane.showConfirmDialog(frame, "Restore the packaged " + kind.title.toLowerCase() + "? Your current revision stays in History.", "Restore default", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
    if (choice != JOptionPane.OK_OPTION) return;
    try {
      store.restoreDefault(kind);
      frame.toast("Restored the packaged " + kind.title.toLowerCase(), Theme.p.allow);
      load();
      frame.state.changed();
    } catch (Exception error) {
      frame.toast(Ui.message(error), Theme.p.deny);
    }
  }

  private void buildList() {
    list.removeAll();
    list.add(Ui.gap(Ui.section("Prompts"), 8));
    for (var each : PromptStore.Kind.values()) list.add(new Entry(each));
    list.add(Ui.gap(Ui.divider(), 6));
    list.add(Ui.gap(Ui.section("How they're used"), 6));
    list.add(Ui.wrap("Classifier → describes each request.\nController → applies the instructor policy.\nSystem prompt → shapes admitted answers.", 12f, Theme.p.faint));
    list.add(Ui.gap(Ui.divider(), 6));
    list.add(Ui.gap(Ui.section("Stored in"), 6));
    list.add(Ui.wrap(store.root().resolve("prompts").toString(), 11f, Theme.p.faint));
    list.revalidate();
    list.repaint();
  }

  private final class Entry extends JComponent {
    private final PromptStore.Kind entry;
    private boolean hover;

    Entry(PromptStore.Kind entry) {
      this.entry = entry;
      Ui.hover(this, () -> { hover = true; repaint(); }, () -> { hover = false; repaint(); });
      Ui.click(this, () -> select(entry));
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(100, 50);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      var selected = entry == kind;
      if (selected || hover) {
        g.setColor(selected ? Theme.p.selected : Theme.p.hover);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
      }
      Ui.icon(entry == PromptStore.Kind.CLASSIFIER ? "shield" : "chat", 16, selected ? Theme.p.accent : Theme.p.muted).paintIcon(this, g, 10, 9);
      g.setFont(Theme.ui(13f, selected ? Font.BOLD : Font.PLAIN));
      g.setColor(Theme.p.text);
      g.drawString(entry.title, 34, 21);
      g.setFont(Theme.ui(11f));
      g.setColor(Theme.p.faint);
      String detail;
      try {
        detail = (store.custom(entry) ? "Custom · " : "Default · ") + Ui.shortSha(Course.hash(store.text(entry)));
      } catch (Exception error) {
        detail = "Unreadable";
      }
      g.drawString(detail, 34, 38);
      g.dispose();
    }
  }

  // ------------------------------------------------------------------ menus

  private void presetsMenu(JComponent anchor) {
    var menu = new JPopupMenu();
    for (var preset : PromptStore.presets(kind)) {
      var item = new JMenuItem("<html><b>" + preset.title() + "</b><br><span style='font-size:9px;color:" + Theme.hex(Theme.p.faint) + "'>" + preset.description() + "</span></html>");
      item.addActionListener(event -> {
        editor.setText(PromptStore.presetText(preset));
        editor.setCaretPosition(0);
        if (note.getText().isBlank()) note.setText("Preset: " + preset.title());
      });
      menu.add(item);
    }
    menu.show(anchor, 0, anchor.getHeight() + 4);
  }

  private void historyMenu(JComponent anchor) {
    var menu = new JPopupMenu();
    try {
      var revisions = store.history(kind);
      if (revisions.isEmpty()) {
        var none = new JMenuItem("No saved revisions yet — the packaged default is active");
        none.setEnabled(false);
        menu.add(none);
      }
      for (var revision : revisions.stream().limit(20).toList()) {
        var when = WHEN.format(java.time.Instant.parse(revision.savedAt()));
        var label = when + " · " + (revision.action().equals("default") ? "default" : "saved") + " · " + Ui.shortSha(revision.sha256()) + (revision.note().isBlank() ? "" : " — " + revision.note());
        var item = new JMenuItem(label.length() > 90 ? label.substring(0, 89) + "…" : label);
        item.addActionListener(event -> {
          try {
            editor.setText(store.revision(kind, revision.sha256()));
            editor.setCaretPosition(0);
            note.setText("Restored revision " + Ui.shortSha(revision.sha256()));
          } catch (Exception error) {
            frame.toast(Ui.message(error), Theme.p.deny);
          }
        });
        menu.add(item);
      }
    } catch (Exception error) {
      menu.add(new JMenuItem(Ui.message(error)));
    }
    menu.show(anchor, 0, anchor.getHeight() + 4);
  }

  // ------------------------------------------------------------------ preview and test

  private void showPreview() {
    lower.removeAll();
    var head = new JPanel(new BorderLayout());
    head.setOpaque(false);
    head.add(Ui.label("Assembled prompt for a sample request (draft text, not yet saved)", 12.5f, Font.BOLD, Theme.p.muted), BorderLayout.WEST);
    head.add(close(), BorderLayout.EAST);
    lower.add(head, BorderLayout.NORTH);
    String text;
    try {
      text = kind == PromptStore.Kind.CLASSIFIER
          ? Prompts.classification(editor.getText(), List.of(Map.of("id", "preview", "prompt", "Explain how fork differs from exec.", "assignment", "a1", "history", List.of())))
          : "── system prompt ──\n" + PromptStore.normalize(editor.getText()) + "\n── user message ──\n" + Bridge.request(sampleScope(), "Explain how fork differs from exec.");
    } catch (Exception error) {
      text = Ui.message(error);
    }
    var area = new JTextArea(text);
    area.setFont(Theme.mono(11.5f));
    area.setEditable(false);
    area.setLineWrap(true);
    area.setWrapStyleWord(false);
    area.setForeground(Theme.p.muted);
    area.setBackground(Theme.p.code);
    area.setBorder(Ui.pad(10, 12, 10, 12));
    area.setCaretPosition(0);
    var scroll = new JScrollPane(area);
    scroll.setBorder(javax.swing.BorderFactory.createLineBorder(Theme.p.border));
    lower.add(scroll, BorderLayout.CENTER);
    openLower();
  }

  private Policy.Scope sampleScope() {
    var active = frame.state.active;
    if (active != null) {
      var scope = active.policy().scopes().stream().filter(s -> !s.id().equals("*")).findFirst().orElse(active.policy().scopes().get(0));
      return scope;
    }
    var evidence = List.of(new Policy.Evidence("syllabus.md", "Concept explanations are allowed."));
    return new Policy.Scope("a1", "Sample assignment", List.of(
        new Policy.Rule(List.of("concept", "hint", "test_design", "logistics"), "allow", false, evidence),
        new Policy.Rule(List.of("debug"), "allow", true, evidence),
        new Policy.Rule(List.of("test_code", "pseudocode", "implementation", "full_solution", "writeup"), "deny", false, evidence)));
  }

  private void showTest() {
    lower.removeAll();
    var head = new JPanel(new BorderLayout());
    head.setOpaque(false);
    head.add(Ui.label("Try this draft on one request — a live classifier call that saves its evidence", 12.5f, Font.BOLD, Theme.p.muted), BorderLayout.WEST);
    head.add(close(), BorderLayout.EAST);
    lower.add(head, BorderLayout.NORTH);
    var form = new Ui.Stack(8);
    form.setBorder(Ui.pad(8, 0, 4, 0));
    var request = new JTextField("Implement only parse_command; I will do the rest.");
    request.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "arc: 10; margin: 5,8,5,8");
    var active = frame.state.active;
    var scopes = active == null ? List.of("a1") : active.policy().scopes().stream().map(Policy.Scope::id).toList();
    var assignment = new javax.swing.JComboBox<>(scopes.toArray(String[]::new));
    if (scopes.contains("a1")) assignment.setSelectedItem("a1");
    var model = new javax.swing.JComboBox<>(new String[] {"gpt-5.6-luna", "gpt-5.6-terra"});
    var run = Ui.primary("Run classifier", "play");
    var row = new JPanel(new BorderLayout(8, 0));
    row.setOpaque(false);
    row.add(request, BorderLayout.CENTER);
    var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    right.setOpaque(false);
    right.add(assignment);
    right.add(model);
    right.add(run);
    row.add(right, BorderLayout.EAST);
    row.setPreferredSize(new Dimension(10, 34));
    form.add(row);
    var output = new Ui.Stack(6);
    form.add(output);
    lower.add(Ui.scroll(form), BorderLayout.CENTER);
    run.addActionListener(event -> {
      var unavailable = Models.unavailable((String) model.getSelectedItem());
      if (unavailable != null) {
        frame.toast(unavailable, Theme.p.clarify);
        return;
      }
      output.removeAll();
      var spinner = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
      spinner.setOpaque(false);
      spinner.add(new Ui.Spinner(14));
      spinner.add(Ui.label("Classifying with " + Models.label((String) model.getSelectedItem()) + " using the draft prompt…", 12.5f, Font.PLAIN, Theme.p.muted));
      spinner.setPreferredSize(new Dimension(10, 22));
      output.add(spinner);
      output.revalidate();
      run.setEnabled(false);
      var draft = editor.getText();
      var started = System.nanoTime();
      Ui.async(() -> {
        var runs = PromptStore.home().resolve("admin-tests");
        var features = Workspace.codex(runs, (String) model.getSelectedItem(), () -> draft)
            .extract("admin-test", request.getText(), (String) assignment.getSelectedItem(), List.of());
        var decision = active == null ? null : Classifier.decide(active.policy(), (String) assignment.getSelectedItem(), features);
        return new Object[] {features, decision};
      }, result -> {
        run.setEnabled(true);
        output.removeAll();
        var features = (Classifier.Features) result[0];
        var decision = (Classifier.Decision) result[1];
        var chipsRow = new Ui.Wrap(6, 6);
        if (decision != null) chipsRow.add(Ui.decision(decision.action()));
        for (var activity : features.activities()) chipsRow.add(Ui.Chip.of(Ui.ACTIVITY.getOrDefault(activity, activity), Theme.p.accent));
        chipsRow.add(Ui.Chip.neutral(features.hasAttempt() ? "attempt shown" : "no attempt"));
        if (features.unclear()) chipsRow.add(Ui.Chip.of("unclear", Theme.p.clarify));
        if (features.dishonest()) chipsRow.add(Ui.Chip.of("dishonest", Theme.p.deny));
        chipsRow.add(Ui.Chip.neutral(Ui.seconds((System.nanoTime() - started) / 1_000_000)));
        output.add(chipsRow);
        output.add(Ui.wrap("Rationale: " + features.rationale(), 12.5f, Theme.p.muted));
        if (decision != null) output.add(Ui.wrap("Controller (active " + frame.state.course() + " policy): " + decision.reason().replace('_', ' ') + " → " + decision.action(), 12.5f, Theme.p.muted));
        output.add(Ui.wrap("Evidence saved under " + PromptStore.home().resolve("admin-tests"), 11f, Theme.p.faint));
        output.revalidate();
        output.repaint();
      }, error -> {
        run.setEnabled(true);
        output.removeAll();
        output.add(new Ui.IconText("alert", "Classifier failed: " + Ui.message(error), 12.5f, Theme.p.deny));
        output.revalidate();
      });
    });
    openLower();
  }

  private JButton close() {
    var close = Ui.ghost("", "x");
    close.addActionListener(event -> {
      lower.setVisible(false);
      revalidate();
    });
    return close;
  }

  private void openLower() {
    lower.setVisible(true);
    lower.revalidate();
    var height = split.getHeight();
    split.setDividerLocation(Math.max(160, height - 260));
    repaint();
  }

  /** Logical line numbers that stay aligned when long lines wrap. */
  private static final class Gutter extends JComponent {
    private final JTextArea area;

    Gutter(JTextArea area) {
      this.area = area;
      setFont(Theme.mono(11f));
      area.getDocument().addDocumentListener(new DocumentListener() {
        public void insertUpdate(DocumentEvent event) { refresh(); }
        public void removeUpdate(DocumentEvent event) { refresh(); }
        public void changedUpdate(DocumentEvent event) { refresh(); }
      });
      area.addComponentListener(new java.awt.event.ComponentAdapter() {
        @Override
        public void componentResized(java.awt.event.ComponentEvent event) {
          refresh();
        }
      });
    }

    private void refresh() {
      revalidate();
      repaint();
    }

    @Override
    public Dimension getPreferredSize() {
      var digits = String.valueOf(Math.max(99, area.getLineCount())).length();
      return new Dimension(getFontMetrics(getFont()).charWidth('0') * digits + 22, area.getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      g.setColor(Theme.p.code);
      g.fillRect(0, 0, getWidth(), getHeight());
      g.setFont(getFont());
      var metrics = g.getFontMetrics();
      var clip = g.getClipBounds();
      var editorMetrics = area.getFontMetrics(area.getFont());
      for (int line = 0; line < area.getLineCount(); line++) {
        try {
          Rectangle bounds = area.modelToView2D(area.getLineStartOffset(line)).getBounds();
          if (bounds.y + bounds.height < clip.y) continue;
          if (bounds.y > clip.y + clip.height) break;
          var label = String.valueOf(line + 1);
          g.setColor(area.getCaretPosition() >= area.getLineStartOffset(line) && area.getCaretPosition() <= area.getLineEndOffset(line) ? Theme.p.muted : Theme.p.faint);
          var baseline = bounds.y + editorMetrics.getAscent() + (editorMetrics.getHeight() - metrics.getHeight()) / 2;
          g.drawString(label, getWidth() - 10 - metrics.stringWidth(label), baseline);
        } catch (Exception ignored) {
          // A concurrent edit can invalidate offsets mid-paint; the next repaint is consistent.
        }
      }
      g.dispose();
    }
  }
}

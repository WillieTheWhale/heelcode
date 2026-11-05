package edu.heelcode.policy.gui;

import edu.heelcode.policy.CodexModel;
import edu.heelcode.policy.Course;
import edu.heelcode.policy.Fixtures;
import edu.heelcode.policy.Json;
import edu.heelcode.policy.Models;
import edu.heelcode.policy.Policy;
import edu.heelcode.policy.PolicyReview;
import edu.heelcode.policy.PromptStore;
import edu.heelcode.policy.Prompts;
import edu.heelcode.policy.Workspace;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Timer;

/**
 * Policy generator and viewer. Generation is explicit: ingest sources, draft with Luna or Terra,
 * have an instructor review every cell, then activate the reviewed policy in a workspace. The
 * generated evidence is preserved; reviews are written beside it.
 */
final class PolicyView extends JPanel implements MainFrame.Refreshable {
  private final MainFrame frame;
  private final CardLayout cards = new CardLayout();
  private final JPanel body = new JPanel(cards);
  private final JToggleButton activeTab = tab("Active policy");
  private final JToggleButton generateTab = tab("Generate draft");

  // Active policy
  private final PolicyMatrix activeMatrix = new PolicyMatrix();
  private final Ui.Stack activeDetails = new Ui.Stack(8);
  private final Ui.Stack activeHead = new Ui.Stack(6);

  // Generator
  private final JTextField courseId = field("sys301");
  private final JTextField sources = field("");
  private final Ui.Stack ingested = new Ui.Stack(4);
  private final JToggleButton luna = segment("Luna");
  private final JToggleButton terra = segment("Terra");
  private final JLabel draftStatus = Ui.label("", 12.5f, Font.PLAIN, Theme.p.muted);
  private final JButton generate = Ui.primary("Generate draft", "spark");
  private final PolicyMatrix reviewMatrix = new PolicyMatrix();
  private final Ui.Stack cellEditor = new Ui.Stack(8);
  private final Ui.Stack checks = new Ui.Stack(4);
  private final JTextField workspace = field("");
  private final JTextField reviewNote = field("");
  private final JButton activate = Ui.primary("Activate policy", "check");
  private Course course;
  private Policy generated;
  private Policy reviewed;
  private Path run;
  private Timer ticker;
  private boolean generating;

  PolicyView(MainFrame frame) {
    super(new BorderLayout());
    this.frame = frame;
    setOpaque(false);

    var head = new JPanel(new BorderLayout());
    head.setOpaque(false);
    head.setBorder(Ui.pad(22, 32, 14, 32));
    var titles = new Ui.Stack(4);
    titles.add(Ui.wrap("Compile an instructor's course documents into assignment rules with verbatim citations, review every cell, then activate the policy for a course.", 14f, Theme.p.muted));
    head.add(titles, BorderLayout.CENTER);
    var tabs = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    tabs.setOpaque(false);
    var group = new ButtonGroup();
    group.add(activeTab);
    group.add(generateTab);
    tabs.add(activeTab);
    tabs.add(generateTab);
    head.add(tabs, BorderLayout.EAST);
    add(head, BorderLayout.NORTH);
    activeTab.addActionListener(event -> cards.show(body, "active"));
    generateTab.addActionListener(event -> cards.show(body, "generate"));

    body.setOpaque(false);
    body.add(activePane(), "active");
    body.add(generatorPane(), "generate");
    add(body, BorderLayout.CENTER);
    activeTab.setSelected(true);
    frame.state.listen(this::refresh);
    refresh();
  }

  @Override
  public void refresh() {
    if ("policies".equals(frame.view)) frame.title("Policies", frame.state.active == null ? "" : frame.state.course());
    var state = frame.state;
    activeHead.removeAll();
    if (state.active == null) {
      activeHead.add(Ui.wrap(state.workspace == null ? "No workspace is open. Generate a draft, or open the SYS 301 demo from the sidebar." : state.problem, 13f, Theme.p.muted));
      activeMatrix.show(null, null);
    } else {
      var active = state.active;
      var chips = new Ui.Wrap(6, 6);
      chips.add(Ui.Chip.of("Active", Theme.p.allow));
      chips.add(Ui.Chip.neutral(active.course().courseId() + " · " + active.policy().scopes().size() + " scopes"));
      chips.add(Ui.Chip.neutral("policy " + Ui.shortSha(active.policyDigest())));
      chips.add(Ui.Chip.neutral("sources " + Ui.shortSha(active.course().digest())));
      chips.add(Ui.Chip.neutral(active.course().sources().size() + " source files"));
      activeHead.add(chips);
      activeHead.add(Ui.wrap(active.policy().version(), 12.5f, Theme.p.muted));
      activeHead.add(Ui.wrap("Sources: " + active.sourceRoot(), 11.5f, Theme.p.faint));
      activeMatrix.show(active.policy(), null);
    }
    activeHead.revalidate();
    if (workspace.getText().isBlank() && state.workspace != null) workspace.setText(state.workspace.toString());
    if (sources.getText().isBlank() && Fixtures.sys301Sources() != null) sources.setText(Fixtures.sys301Sources().toString());
  }

  // ------------------------------------------------------------------ active

  private JComponent activePane() {
    var page = new Ui.Stack(16);
    page.setBorder(Ui.pad(4, 32, 28, 32));
    page.add(activeHead);
    var matrixCard = new Ui.Stack(12).card(Theme.p.surface, Theme.p.subtle);
    matrixCard.setBorder(Ui.pad(14, 14, 14, 14));
    matrixCard.add(legend());
    matrixCard.add(activeMatrix);
    page.add(matrixCard);
    activeDetails.add(Ui.wrap("Select a cell to see the rule and the verbatim source passages behind it.", 12.5f, Theme.p.faint));
    page.add(activeDetails);
    activeMatrix.onSelect = (scope, activity) -> showEvidence(activeDetails, activeMatrix.policy, scope, activity, false);
    return Ui.scroll(page);
  }

  private JComponent legend() {
    var row = new Ui.Wrap(8, 6);
    row.add(Ui.Chip.of("allow", Theme.p.allow));
    row.add(Ui.Chip.of("deny", Theme.p.deny));
    row.add(Ui.Chip.of("ask instructor", Theme.p.clarify));
    row.add(new Ui.Chip("attempt required", Theme.p.muted, Theme.p.dark ? Theme.p.raised : Theme.p.hover, Ui.icon("clock", 11, Theme.p.clarify)));
    return row;
  }

  private void showEvidence(Ui.Stack target, Policy policy, String scope, String activity, boolean editable) {
    target.removeAll();
    var cell = PolicyReview.cell(policy, scope, activity);
    var card = new Ui.Stack(8).card(Theme.p.surface, Theme.p.subtle);
    card.setBorder(Ui.pad(14, 16, 14, 16));
    var heading = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    heading.setOpaque(false);
    heading.add(Ui.label((scope.equals("*") ? "Course baseline" : scope) + " · " + Ui.ACTIVITY.get(activity), 14f, Font.BOLD, Theme.p.text));
    heading.add(Ui.Chip.of(cell.effect() + (cell.requireAttempt() ? " · attempt required" : ""), Theme.effect(cell.effect())));
    heading.setPreferredSize(new Dimension(10, 26));
    card.add(heading);
    if (editable) {
      var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
      controls.setOpaque(false);
      var group = new ButtonGroup();
      for (var effect : new String[] {"allow", "clarify", "deny"}) {
        var button = segment(effect.equals("clarify") ? "Ask instructor" : Character.toUpperCase(effect.charAt(0)) + effect.substring(1));
        button.setSelected(cell.effect().equals(effect));
        group.add(button);
        button.addActionListener(event -> edit(scope, activity, effect, cell.requireAttempt() && effect.equals("allow")));
        controls.add(button);
      }
      var attempt = new JCheckBox("Require a concrete attempt first");
      attempt.setOpaque(false);
      attempt.setSelected(cell.requireAttempt());
      attempt.setEnabled(cell.effect().equals("allow"));
      attempt.addActionListener(event -> edit(scope, activity, cell.effect(), attempt.isSelected()));
      controls.add(javax.swing.Box.createHorizontalStrut(14));
      controls.add(attempt);
      controls.setPreferredSize(new Dimension(10, 32));
      card.add(controls);
    }
    for (var evidence : cell.evidence()) {
      var quote = evidence.quote().strip();
      card.add(new Ui.IconText("quote", quote + "\n— " + evidence.source(), 12.5f, Theme.p.muted));
    }
    target.add(card);
    target.revalidate();
    target.repaint();
  }

  // ------------------------------------------------------------------ generator

  private JComponent generatorPane() {
    var page = new Ui.Stack(14);
    page.setBorder(Ui.pad(4, 32, 32, 32));
    page.maxWidth = 1040;

    var sourcesBrowse = Ui.secondary("Browse…", "folder");
    sourcesBrowse.addActionListener(event -> choose(sources, "Choose the instructor's course folder"));
    var ingest = Ui.secondary("Ingest", "download");
    ingest.addActionListener(event -> ingest());
    var sourcesRow = new JPanel(new BorderLayout(8, 0));
    sourcesRow.setOpaque(false);
    var idBox = new JPanel(new BorderLayout(6, 0));
    idBox.setOpaque(false);
    idBox.add(Ui.muted("Course ID"), BorderLayout.WEST);
    courseId.setColumns(8);
    idBox.add(courseId, BorderLayout.CENTER);
    sourcesRow.add(idBox, BorderLayout.WEST);
    sourcesRow.add(sources, BorderLayout.CENTER);
    sourcesRow.add(Ui.row(6, sourcesBrowse, ingest), BorderLayout.EAST);
    sourcesRow.setPreferredSize(new Dimension(10, 34));
    page.add(step(1, "Course sources", "UTF-8 Markdown or text files only. Symlinks and other formats are refused, not skipped. Sources are evidence, never instructions to the generator.", sourcesRow, ingested));

    var models = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    models.setOpaque(false);
    var group = new ButtonGroup();
    group.add(luna);
    group.add(terra);
    luna.setSelected(true);
    models.add(luna);
    models.add(terra);
    var open = Ui.secondary("Open saved run…", "folder");
    open.addActionListener(event -> chooseRun());
    generate.addActionListener(event -> generate());
    var draftRow = new JPanel(new BorderLayout(10, 0));
    draftRow.setOpaque(false);
    draftRow.add(models, BorderLayout.WEST);
    draftRow.add(draftStatus, BorderLayout.CENTER);
    draftRow.add(Ui.row(8, open, generate), BorderLayout.EAST);
    draftRow.setPreferredSize(new Dimension(10, 34));
    page.add(step(2, "Draft the policy", "A tool-free Codex call compiles every assignment scope plus a course baseline. Missing activities, overlaps, and quotes that are not verbatim fail validation. No silent fallback model.", draftRow));

    var review = new Ui.Stack(12);
    review.add(legend());
    review.add(reviewMatrix);
    review.add(cellEditor);
    review.add(checks);
    reviewMatrix.onSelect = (scope, activity) -> showEvidence(cellEditor, reviewed, scope, activity, true);
    page.add(step(3, "Instructor review", "A valid policy is still a draft. Real citations can still be misread (the September NotebookLM drafts did exactly that), so check each cell. Edits are recorded beside the generated evidence.", review));

    var browse = Ui.secondary("Browse…", "folder");
    browse.addActionListener(event -> choose(workspace, "Choose the course workspace to activate"));
    var activateRow = new JPanel(new BorderLayout(8, 0));
    activateRow.setOpaque(false);
    activateRow.add(Ui.muted("Workspace"), BorderLayout.WEST);
    activateRow.add(workspace, BorderLayout.CENTER);
    activateRow.add(Ui.row(8, browse), BorderLayout.EAST);
    activateRow.setPreferredSize(new Dimension(10, 34));
    var noteRow = new JPanel(new BorderLayout(8, 0));
    noteRow.setOpaque(false);
    reviewNote.putClientProperty(com.formdev.flatlaf.FlatClientProperties.PLACEHOLDER_TEXT, "Review note, e.g. who approved it and what changed");
    noteRow.add(reviewNote, BorderLayout.CENTER);
    activate.addActionListener(event -> activate());
    activate.setEnabled(false);
    noteRow.add(activate, BorderLayout.EAST);
    noteRow.setPreferredSize(new Dimension(10, 34));
    page.add(step(4, "Activate", "Activation pins the policy and source digests to the workspace. If a source changes later, requests fail closed until the policy is regenerated and reactivated.", activateRow, noteRow));
    reviewMatrix.show(null, null);
    return Ui.scroll(page);
  }

  private JComponent step(int number, String title, String description, JComponent... content) {
    var card = new Ui.Stack(10).card(Theme.p.surface, Theme.p.subtle);
    card.setBorder(Ui.pad(18, 20, 18, 20));
    var heading = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    heading.setOpaque(false);
    heading.add(Ui.label(number + ".", 15f, Font.PLAIN, Theme.p.faint));
    heading.add(Ui.label(title, 15f, Font.BOLD, Theme.p.emphasis));
    heading.setPreferredSize(new Dimension(10, 26));
    card.add(heading);
    card.add(Ui.wrap(description, 12.5f, Theme.p.muted));
    for (var component : content) card.add(component);
    return card;
  }

  private void ingest() {
    try {
      course = Course.ingest(courseId.getText().strip(), Path.of(sources.getText().strip()));
      ingested.removeAll();
      var total = course.sources().stream().mapToInt(source -> source.text().length()).sum();
      ingested.add(Ui.label(course.sources().size() + " sources · " + String.format("%,d", total) + " characters · digest " + Ui.shortSha(course.digest()), 12.5f, Font.BOLD, Theme.p.allow));
      var grid = new JPanel(new GridLayout(0, 3, 12, 2));
      grid.setOpaque(false);
      for (var source : course.sources()) {
        grid.add(Ui.label(source.id(), 12f, Font.PLAIN, Theme.p.text));
        grid.add(Ui.label(String.format("%,d chars", source.text().length()), 12f, Font.PLAIN, Theme.p.faint));
        grid.add(Ui.label("sha " + Ui.shortSha(source.sha256()), 12f, Font.PLAIN, Theme.p.faint));
      }
      ingested.add(grid);
      draftStatus.setText("Ready to draft " + course.courseId());
    } catch (Exception error) {
      course = null;
      ingested.removeAll();
      ingested.add(new Ui.IconText("alert", "Refused: " + Ui.message(error), 12.5f, Theme.p.deny));
    }
    ingested.revalidate();
    ingested.repaint();
  }

  boolean generating() {
    return generating;
  }

  /** Scripted use: the same path as choosing a model and pressing Generate draft. */
  void generateWith(String model) {
    (model.equals("gpt-5.6-terra") ? terra : luna).setSelected(true);
    generateTab.setSelected(true);
    cards.show(body, "generate");
    generate();
  }

  /** Scripted use: change one reviewed cell, exactly as the cell editor does. */
  void editCell(String scope, String activity, String effect, boolean attempt) {
    if (reviewed == null) throw new IllegalStateException("No draft to review");
    edit(scope, activity, effect, attempt);
    reviewMatrix.scope = scope;
    reviewMatrix.activity = activity;
  }

  /** Scripted use: activate the reviewed draft in a workspace, as the Activate button does. */
  void activateTo(Path target, String note) {
    workspace.setText(target.toString());
    reviewNote.setText(note);
    activate();
  }

  private void generate() {
    if (course == null) ingest();
    if (course == null) return;
    var model = luna.isSelected() ? "gpt-5.6-luna" : "gpt-5.6-terra";
    var unavailable = Models.unavailable(model);
    if (unavailable != null) {
      frame.toast(unavailable, Theme.p.clarify);
      return;
    }
    var stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
    var target = PromptStore.home().resolve("runs/" + course.courseId() + "-" + stamp + "-" + model.replace("gpt-5.6-", ""));
    generate.setEnabled(false);
    generating = true;
    var started = System.nanoTime();
    ticker = new Timer(200, event -> draftStatus.setText(Models.label(model) + " is drafting… " + Ui.seconds((System.nanoTime() - started) / 1_000_000)));
    ticker.start();
    var snapshot = course;
    Ui.async(() -> {
      var result = new CodexModel(model).generate(Prompts.generation(snapshot), Prompts.policySchema(), target);
      Json.write(target.resolve("bundle.json"), snapshot);
      var policy = Json.MAPPER.treeToValue(result, Policy.class);
      policy.validate(snapshot);
      Json.write(target.resolve("policy.json"), policy);
      return policy;
    }, policy -> {
      ticker.stop();
      generating = false;
      generate.setEnabled(true);
      run = target;
      loadDraft(policy, "Drafted by " + Models.label(model) + " in " + Ui.seconds((System.nanoTime() - started) / 1_000_000) + " · validated");
    }, error -> {
      ticker.stop();
      generating = false;
      generate.setEnabled(true);
      draftStatus.setText("Draft failed — evidence kept in " + target.getFileName());
      frame.toast("Generation failed: " + Ui.message(error), Theme.p.deny);
    });
  }

  private void chooseRun() {
    var chooser = new JFileChooser(Fixtures.sys301Run() == null ? PromptStore.home().toFile() : Fixtures.sys301Run().getParent().toFile());
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle("Open a saved generation run (bundle.json + policy.json)");
    if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    openRun(chooser.getSelectedFile().toPath());
  }

  void openRun(Path directory) {
    try {
      var bundle = Json.read(directory.resolve("bundle.json"), Course.class);
      var policy = Json.read(directory.resolve("policy.json"), Policy.class);
      policy.validate(bundle);
      course = bundle;
      courseId.setText(bundle.courseId());
      run = directory;
      if (bundle.courseId().equals("sys301") && Fixtures.sys301Sources() != null) sources.setText(Fixtures.sys301Sources().toString());
      loadDraft(policy, "Loaded saved run " + directory.getFileName() + " · validated");
    } catch (Exception error) {
      frame.toast("Not a valid run: " + Ui.message(error), Theme.p.deny);
    }
  }

  private void loadDraft(Policy policy, String status) {
    generated = policy;
    reviewed = policy;
    draftStatus.setText(status);
    reviewMatrix.show(reviewed, generated);
    cellEditor.removeAll();
    cellEditor.add(Ui.wrap("Select a cell to review its rule and source passages, or change it.", 12.5f, Theme.p.faint));
    validation();
    activate.setEnabled(true);
    generateTab.setSelected(true);
    cards.show(body, "generate");
    revalidate();
  }

  private void edit(String scope, String activity, String effect, boolean attempt) {
    reviewed = PolicyReview.edit(reviewed, scope, activity, effect, attempt);
    reviewMatrix.show(reviewed, generated);
    showEvidence(cellEditor, reviewed, scope, activity, true);
    validation();
  }

  private void validation() {
    checks.removeAll();
    var changes = PolicyReview.diff(generated, reviewed);
    try {
      reviewed.validate(course);
      var citations = reviewed.scopes().stream().flatMap(s -> s.rules().stream()).mapToLong(r -> r.evidence().size()).sum();
      checks.add(new Ui.IconText("check", "Course identity matches " + course.courseId() + "; baseline scope present; " + reviewed.scopes().size() + " scopes each cover all 10 activities exactly once.", 12.5f, Theme.p.allow));
      checks.add(new Ui.IconText("check", citations + " citations are verbatim passages from the ingested sources. (Quote existence does not prove the rule reads the quote correctly.)", 12.5f, Theme.p.allow));
    } catch (Exception error) {
      checks.add(new Ui.IconText("alert", "Validation failed: " + Ui.message(error), 12.5f, Theme.p.deny));
    }
    checks.add(new Ui.IconText(changes.isEmpty() ? "eye" : "edit", changes.isEmpty() ? "No instructor edits yet." : changes.size() + " instructor edit" + (changes.size() == 1 ? "" : "s") + " (dashed outline). They will be saved to a review folder in the run before activation.", 12.5f, changes.isEmpty() ? Theme.p.faint : Theme.p.accent));
    checks.revalidate();
    checks.repaint();
  }

  private void activate() {
    if (reviewed == null || course == null) return;
    var target = Path.of(workspace.getText().strip());
    try {
      var changes = PolicyReview.diff(generated, reviewed);
      if (!changes.isEmpty() && run != null && Files.isWritable(run)) PolicyReview.save(run, generated, reviewed, reviewNote.getText());
      Workspace.activate(target, Path.of(sources.getText().strip()), course, reviewed);
      frame.state.open(target);
      frame.toast("Activated " + course.courseId() + (changes.isEmpty() ? "" : " with " + changes.size() + " instructor edits") + " in " + target.getFileName(), Theme.p.allow);
      activeTab.setSelected(true);
      cards.show(body, "active");
      refresh();
    } catch (Exception error) {
      frame.toast("Activation refused: " + Ui.message(error), Theme.p.deny);
    }
  }

  private void choose(JTextField target, String title) {
    var chooser = new JFileChooser(target.getText().isBlank() ? null : Path.of(target.getText()).toFile());
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle(title);
    if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) target.setText(chooser.getSelectedFile().getAbsolutePath());
  }

  private static JTextField field(String text) {
    var field = new JTextField(text);
    field.setFont(Theme.ui(13.5f));
    field.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "arc: 10; margin: 6,10,6,10; background: " + Theme.hex(Theme.p.dark ? Theme.p.composer : Theme.p.surface) + "; borderColor: " + Theme.hex(Theme.p.border));
    return field;
  }

  /** Codex segmented control: transparent pills, the selected one filled. */
  private static JToggleButton tab(String text) {
    var button = new JToggleButton(text);
    button.setFont(Theme.medium(13f));
    Ui.style(button, "arc: 999; borderWidth: 0; focusWidth: 0; margin: 6,14,6,14; background: " + Theme.hex(Theme.alpha(Theme.p.bg, 0))
        + "; selectedBackground: " + Theme.hex(Theme.p.selected) + "; selectedForeground: " + Theme.hex(Theme.p.emphasis)
        + "; foreground: " + Theme.hex(Theme.p.muted) + "; hoverBackground: " + Theme.hex(Theme.p.hover));
    return button;
  }

  private static JToggleButton segment(String text) {
    var button = new JToggleButton(text);
    button.setFont(Theme.medium(13f));
    Ui.style(button, "arc: 999; borderWidth: 0; focusWidth: 0; margin: 5,13,5,13; background: " + Theme.hex(Theme.alpha(Theme.p.bg, 0))
        + "; selectedBackground: " + Theme.hex(Theme.p.selected) + "; selectedForeground: " + Theme.hex(Theme.p.emphasis)
        + "; foreground: " + Theme.hex(Theme.p.muted) + "; hoverBackground: " + Theme.hex(Theme.p.hover));
    return button;
  }
}

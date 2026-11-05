package edu.heelcode.policy.gui;

import edu.heelcode.policy.Json;
import edu.heelcode.policy.Models;
import edu.heelcode.policy.TestBench;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Runs frozen suites through the real controller: replaying gold/recorded features tests the
 * policy logic and every rejection path with no model calls; a live source re-extracts features
 * and measures the classifier end to end. Unsafe allowances are the headline safety number.
 */
final class TestsView extends JPanel implements MainFrame.Refreshable {
  private final MainFrame frame;
  private final Ui.Stack suites = new Ui.Stack(6);
  private final List<TestBench.Suite> available = new ArrayList<>();
  private TestBench.Suite suite;
  private final JToggleButton recorded = segment("Recorded");
  private final JToggleButton luna = segment("Luna");
  private final JToggleButton terra = segment("Terra");
  private final JToggleButton jev = segment("Jev");
  private final javax.swing.JButton run = Ui.primary("Run suite", "play");
  private final JLabel status = Ui.label("", 12.5f, Font.PLAIN, Theme.p.muted);
  private final JLabel suiteTitle = Ui.label("", 20f, Font.BOLD, Theme.p.emphasis);
  private final javax.swing.JTextArea suiteText = Ui.wrap("", 12.5f, Theme.p.muted);
  private final JPanel summary = new JPanel(new GridLayout(1, 4, 10, 0));
  private final Confusion confusion = new Confusion();
  private final Rows rows = new Rows();
  private final JTable table = new JTable(rows);
  private final JToggleButton failures = segment("Failures only");
  private final Ui.Stack detail = new Ui.Stack(8);

  TestsView(MainFrame frame) {
    super(new BorderLayout());
    this.frame = frame;
    setOpaque(false);

    var side = new JPanel(new BorderLayout()) {
      @Override
      protected void paintComponent(Graphics g) {
        // The inset panel behind paints the surface (and its rounded corners); only the rule here.
        g.setColor(Theme.p.subtle);
        g.fillRect(getWidth() - 1, 0, 1, getHeight());
      }
    };
    side.setPreferredSize(new Dimension(280, 100));
    suites.setBorder(Ui.pad(16, 10, 12, 10));
    side.add(Ui.scroll(suites), BorderLayout.CENTER);
    add(side, BorderLayout.WEST);

    var main = new JPanel(new BorderLayout());
    main.setOpaque(false);
    var head = new Ui.Stack(10);
    head.setBorder(Ui.pad(22, 32, 10, 32));
    head.add(suiteTitle);
    head.add(suiteText);
    var bar = new JPanel(new BorderLayout(12, 0));
    bar.setOpaque(false);
    var sources = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    sources.setOpaque(false);
    var group = new ButtonGroup();
    for (var button : List.of(recorded, luna, terra, jev)) {
      group.add(button);
      sources.add(button);
      button.addActionListener(event -> updateRunState());
    }
    recorded.setSelected(true);
    bar.add(sources, BorderLayout.WEST);
    bar.add(status, BorderLayout.CENTER);
    bar.add(run, BorderLayout.EAST);
    bar.setPreferredSize(new Dimension(10, 34));
    head.add(bar);
    main.add(head, BorderLayout.NORTH);

    var results = new Ui.Stack(14);
    results.setBorder(Ui.pad(6, 32, 24, 32));
    summary.setOpaque(false);
    summary.setPreferredSize(new Dimension(10, 84));
    results.add(summary);
    var middle = new JPanel(new BorderLayout(14, 0));
    middle.setOpaque(false);
    var confusionCard = new Ui.Stack(8).card(Theme.p.surface, Theme.p.subtle);
    confusionCard.setBorder(Ui.pad(12, 14, 12, 14));
    confusionCard.add(Ui.section("Expected → actual"));
    confusionCard.add(confusion);
    confusionCard.setPreferredSize(new Dimension(300, 196));
    middle.add(confusionCard, BorderLayout.WEST);
    detail.card(Theme.p.surface, Theme.p.subtle);
    detail.setBorder(Ui.pad(12, 14, 12, 14));
    detail.add(Ui.wrap("Select a case to see the request, the extracted features, and the controller's decision.", 12.5f, Theme.p.faint));
    middle.add(detail, BorderLayout.CENTER);
    middle.setPreferredSize(new Dimension(10, 196));
    results.add(new Fixed(middle, 196));

    var tableHead = new JPanel(new BorderLayout());
    tableHead.setOpaque(false);
    tableHead.add(Ui.section("Cases"), BorderLayout.WEST);
    failures.addActionListener(event -> rows.filter(failures.isSelected()));
    tableHead.add(failures, BorderLayout.EAST);
    tableHead.setPreferredSize(new Dimension(10, 28));
    results.add(tableHead);
    table.setRowHeight(34);
    table.setShowVerticalLines(false);
    table.setShowHorizontalLines(true);
    table.setFillsViewportHeight(true);
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table.setFont(Theme.ui(12.5f));
    table.getTableHeader().setFont(Theme.ui(11.5f, Font.BOLD));
    table.getTableHeader().setForeground(Theme.p.faint);
    table.setDefaultRenderer(Object.class, new Renderer());
    var widths = new int[] {34, 92, 74, 420, 92, 92, 200};
    for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
    table.getSelectionModel().addListSelectionListener(event -> {
      if (!event.getValueIsAdjusting() && table.getSelectedRow() >= 0) showRow(rows.row(table.getSelectedRow()));
    });
    var scroll = new JScrollPane(table);
    scroll.setBorder(javax.swing.BorderFactory.createLineBorder(Theme.p.border));
    results.add(new Fixed(scroll, 380));
    main.add(Ui.scroll(results), BorderLayout.CENTER);
    add(main, BorderLayout.CENTER);
    run.addActionListener(event -> runSuite());
    loadSuites();
    emptySummary();
  }

  @Override
  public void refresh() {
    if ("tests".equals(frame.view)) frame.title("Policy tests", suite == null ? "" : suite.title());
    updateRunState();
  }

  private void loadSuites() {
    suites.removeAll();
    suites.add(Ui.gap(Ui.section("Suites"), 8));
    try {
      available.clear();
      available.addAll(TestBench.suites());
    } catch (Exception error) {
      suites.add(Ui.wrap("Suites unavailable: " + Ui.message(error), 12f, Theme.p.deny));
    }
    if (available.isEmpty()) suites.add(Ui.wrap("Fixtures are only present in a HeelCode checkout.", 12f, Theme.p.faint));
    for (var each : available) suites.add(new SuiteItem(each));
    suites.add(Ui.gap(Ui.divider(), 6));
    suites.add(Ui.gap(Ui.section("Sources"), 6));
    suites.add(Ui.wrap("Recorded replays gold or saved features: no model calls, so it tests the policy controller and every rejection path.\n\nLuna · Terra · Jev re-classify the requests live (blinded; no labels sent) and save each model run as evidence.", 11.5f, Theme.p.faint));
    if (!available.isEmpty()) select(available.get(0));
  }

  private void select(TestBench.Suite next) {
    suite = next;
    suiteTitle.setText(next.title());
    suiteText.setText(next.description() + "  ·  " + next.size() + " cases · " + next.parts().size() + (next.parts().size() == 1 ? " course policy" : " course policies"));
    recorded.setText(next.recordedLabel());
    updateRunState();
    suites.repaint();
  }

  private void updateRunState() {
    if (suite == null) {
      run.setEnabled(false);
      return;
    }
    for (var button : List.of(luna, terra, jev)) {
      var model = model(button);
      var reason = !suite.liveAllowed() ? "Too many cases for a live run from the app" : Models.unavailable(model);
      button.setEnabled(reason == null);
      button.setToolTipText(reason);
      if (reason != null && button.isSelected()) recorded.setSelected(true);
    }
    run.setEnabled(true);
  }

  private static String model(JToggleButton button) {
    return switch (button.getText()) {
      case "Luna" -> "gpt-5.6-luna";
      case "Terra" -> "gpt-5.6-terra";
      default -> Models.JEV;
    };
  }

  private boolean running;

  boolean running() {
    return running;
  }

  /** Scripted use: select a suite and source, then run without the live-run confirmation. */
  void runFor(String id, String source) {
    available.stream().filter(each -> each.id().equals(id)).findFirst().ifPresent(this::select);
    for (var button : List.of(recorded, luna, terra, jev))
      button.setSelected(source.equals(TestBench.RECORDED) ? button == recorded : button != recorded && model(button).equals(source));
    execute(true);
  }

  private void runSuite() {
    execute(false);
  }

  private void execute(boolean scripted) {
    if (suite == null) return;
    var source = recorded.isSelected() ? TestBench.RECORDED : luna.isSelected() ? model(luna) : terra.isSelected() ? model(terra) : model(jev);
    var live = !source.equals(TestBench.RECORDED);
    if (live && !scripted) {
      var calls = source.equals(Models.JEV) ? suite.size() + " Jev calls (one per case)" : "one blinded " + Models.label(source) + " batch call";
      var answer = JOptionPane.showConfirmDialog(frame, "Run " + suite.size() + " cases live? This makes " + calls + " and saves the raw evidence.", "Live classifier run", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
      if (answer != JOptionPane.OK_OPTION) return;
    }
    var evidence = live ? TestBench.evidence(suite.id(), source) : null;
    running = true;
    run.setEnabled(false);
    status.setText(live ? "Starting…" : "Replaying " + suite.recordedLabel().toLowerCase() + "…");
    var chosen = suite;
    Ui.async(() -> TestBench.run(chosen, source, evidence, progress -> javax.swing.SwingUtilities.invokeLater(() -> status.setText(progress))), result -> {
      running = false;
      run.setEnabled(true);
      if (evidence != null) {
        try {
          Json.write(evidence.resolve("report.json"), result);
        } catch (Exception ignored) {
          // The on-screen report remains; evidence directories already hold the raw runs.
        }
      }
      status.setText((live ? Models.label(source) : chosen.recordedLabel()) + " · " + result.cases() + " cases · " + Ui.seconds(result.elapsedMs()) + (evidence == null ? "" : " · evidence saved"));
      show(result);
    }, error -> {
      running = false;
      run.setEnabled(true);
      status.setText("Run failed: " + Ui.message(error));
      frame.toast("Run failed — no partial score is reported", Theme.p.deny);
    });
  }

  private void show(TestBench.Report result) {
    summary.removeAll();
    summary.add(metric("Matched", result.correct() + " / " + result.cases(), String.format("%.0f%% of expected decisions", 100.0 * result.correct() / Math.max(1, result.cases())), result.correct() == result.cases() ? Theme.p.allow : Theme.p.clarify));
    summary.add(metric("Unsafe allows", String.valueOf(result.unsafeAllows()), "expected deny → allowed", result.unsafeAllows() == 0 ? Theme.p.allow : Theme.p.deny));
    summary.add(metric("False denials", String.valueOf(result.falseDenials()), "expected allow → denied", result.falseDenials() == 0 ? Theme.p.allow : Theme.p.clarify));
    summary.add(metric("Clarifications", String.valueOf(result.clarifications()), "requests sent back for detail", Theme.p.accent));
    summary.revalidate();
    summary.repaint();
    confusion.report = result;
    confusion.repaint();
    rows.set(result.rows(), failures.isSelected());
    detail.removeAll();
    detail.add(Ui.wrap(result.correct() == result.cases() ? "Every decision matched. Select a case to inspect it." : (result.cases() - result.correct()) + " mismatches. Turn on “Failures only” to review them.", 12.5f, Theme.p.faint));
    detail.revalidate();
  }

  private void emptySummary() {
    summary.removeAll();
    summary.add(metric("Matched", "—", "run a suite to score it", Theme.p.faint));
    summary.add(metric("Unsafe allows", "—", "expected deny → allowed", Theme.p.faint));
    summary.add(metric("False denials", "—", "expected allow → denied", Theme.p.faint));
    summary.add(metric("Clarifications", "—", "requests sent back for detail", Theme.p.faint));
  }

  private JComponent metric(String label, String value, String hint, Color tone) {
    var card = new Ui.Stack(4).card(Theme.p.surface, Theme.p.subtle);
    card.setBorder(Ui.pad(12, 16, 12, 16));
    card.add(Ui.label(label, 13f, Font.PLAIN, Theme.p.faint));
    card.add(Ui.label(value, 24f, Font.BOLD, tone));
    card.add(Ui.label(hint, 12f, Font.PLAIN, Theme.p.faint));
    return card;
  }

  private void showRow(TestBench.Row row) {
    detail.removeAll();
    var heading = new Ui.Wrap(6, 6);
    heading.add(Ui.Chip.neutral(row.id()));
    heading.add(Ui.Chip.neutral(row.course() + " · " + row.assignment()));
    heading.add(Ui.Chip.of("expected " + row.expected(), Theme.effect(row.expected())));
    heading.add(Ui.decision(row.actual()));
    if (row.category() != null) heading.add(Ui.Chip.neutral(row.category().replace('_', ' ')));
    detail.add(heading);
    detail.add(Ui.wrap(row.prompt(), 13f, Theme.p.text));
    var features = row.features();
    detail.add(Ui.wrap("Features: " + (features.activities().isEmpty() ? "none" : Ui.join(features.activities())) + (features.hasAttempt() ? " · attempt" : "") + (features.unclear() ? " · unclear" : "") + (features.dishonest() ? " · dishonest" : "") + " — " + features.rationale(), 12f, Theme.p.muted));
    detail.add(Ui.wrap("Controller: " + row.decision().reason().replace('_', ' ') + " → " + row.actual(), 12f, Theme.p.muted));
    detail.revalidate();
    detail.repaint();
  }

  private final class SuiteItem extends JComponent {
    private final TestBench.Suite item;
    private boolean hover;

    SuiteItem(TestBench.Suite item) {
      this.item = item;
      setToolTipText(item.description());
      Ui.hover(this, () -> { hover = true; repaint(); }, () -> { hover = false; repaint(); });
      Ui.click(this, () -> select(item));
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(100, 52);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      var selected = item == suite;
      if (selected || hover) {
        g.setColor(selected ? Theme.p.selected : Theme.p.hover);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
      }
      Ui.icon(item.id().equals("redteam") ? "shield" : item.id().equals("curriculum") ? "layers" : "flask", 16, selected ? Theme.p.accent : Theme.p.muted).paintIcon(this, g, 10, 10);
      g.setFont(Theme.ui(14f));
      g.setColor(selected ? Theme.p.emphasis : Theme.p.text);
      g.drawString(item.title(), 34, 22);
      g.setFont(Theme.ui(11f));
      g.setColor(Theme.p.faint);
      g.drawString(item.size() + " cases · " + item.recordedLabel().toLowerCase(), 34, 39);
      g.dispose();
    }
  }

  /** 3×3 expected-versus-actual heat map; the deny→allow cell is the safety-critical one. */
  private static final class Confusion extends JComponent {
    TestBench.Report report;

    Confusion() {
      setPreferredSize(new Dimension(260, 150));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      var labels = new String[] {"allow", "deny", "clarify"};
      var left = 62;
      var top = 20;
      var cell = Math.min((getWidth() - left) / 3, (getHeight() - top) / 3);
      g.setFont(Theme.ui(10.5f, Font.BOLD));
      for (int i = 0; i < 3; i++) {
        g.setColor(Theme.p.faint);
        var metrics = g.getFontMetrics();
        g.drawString(labels[i], left + i * cell + (cell - metrics.stringWidth(labels[i])) / 2, 13);
        g.drawString(labels[i], 0, top + i * cell + cell / 2 + 4);
      }
      var max = 1;
      if (report != null) for (var value : report.confusion().values()) max = Math.max(max, value);
      for (int row = 0; row < 3; row++) {
        for (int column = 0; column < 3; column++) {
          var value = report == null ? 0 : report.confusion().get(labels[row] + "->" + labels[column]);
          var diagonal = row == column;
          var unsafe = row != 0 && column == 0;
          var tone = diagonal ? Theme.p.allow : unsafe ? Theme.p.deny : Theme.p.clarify;
          var x = left + column * cell + 2;
          var y = top + row * cell + 2;
          g.setColor(value == 0 ? (Theme.p.dark ? Theme.p.raised : Theme.p.hover) : Theme.alpha(tone, 40 + (int) (150.0 * value / max)));
          g.fillRoundRect(x, y, cell - 4, cell - 4, 8, 8);
          g.setFont(Theme.ui(14f, Font.BOLD));
          g.setColor(value == 0 ? Theme.p.faint : Theme.p.text);
          var text = report == null ? "·" : String.valueOf(value);
          var metrics = g.getFontMetrics();
          g.drawString(text, x + (cell - 4 - metrics.stringWidth(text)) / 2, y + (cell - 4) / 2 + metrics.getAscent() / 2 - 2);
        }
      }
      g.dispose();
    }
  }

  private static final class Rows extends AbstractTableModel {
    private static final String[] COLUMNS = {"", "Case", "Scope", "Request", "Expected", "Actual", "Detected"};
    private List<TestBench.Row> all = List.of();
    private List<TestBench.Row> shown = List.of();

    void set(List<TestBench.Row> rows, boolean failuresOnly) {
      all = rows;
      filter(failuresOnly);
    }

    void filter(boolean failuresOnly) {
      shown = failuresOnly ? all.stream().filter(row -> !row.correct()).toList() : all;
      fireTableDataChanged();
    }

    TestBench.Row row(int index) {
      return shown.get(index);
    }

    public int getRowCount() {
      return shown.size();
    }

    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
      return COLUMNS[column];
    }

    public Object getValueAt(int index, int column) {
      var row = shown.get(index);
      return switch (column) {
        case 0 -> row.correct() ? "✓" : "✗";
        case 1 -> row.id();
        case 2 -> row.assignment();
        case 3 -> row.prompt().replaceAll("\\s+", " ");
        case 4 -> row.expected();
        case 5 -> row.actual();
        default -> String.join(", ", row.features().activities()) + (row.features().dishonest() ? " · dishonest" : "") + (row.features().unclear() ? " · unclear" : "");
      };
    }
  }

  private final class Renderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
      var label = (JLabel) super.getTableCellRendererComponent(table, value, selected, false, row, column);
      label.setBorder(Ui.pad(0, 8, 0, 8));
      label.setFont(Theme.ui(12.5f));
      label.setForeground(Theme.p.text);
      var item = rows.row(row);
      if (column == 0) {
        label.setForeground(item.correct() ? Theme.p.allow : Theme.p.deny);
        label.setFont(Theme.ui(13f, Font.BOLD));
      }
      if (column == 4 || column == 5) {
        label.setForeground(Theme.effect(String.valueOf(value)));
        label.setFont(Theme.ui(12.5f, Font.BOLD));
      }
      if (column == 1 || column == 2 || column == 6) label.setForeground(Theme.p.muted);
      return label;
    }
  }

  /** Gives a non-wrapping child a fixed height inside a stack. */
  private static final class Fixed extends JPanel implements Ui.Flow {
    private final int height;

    Fixed(JComponent child, int height) {
      super(new BorderLayout());
      this.height = height;
      setOpaque(false);
      add(child, BorderLayout.CENTER);
    }

    @Override
    public int heightFor(int width) {
      return height;
    }
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

package edu.heelcode.policy.gui;

import edu.heelcode.policy.Policy;
import edu.heelcode.policy.PolicyReview;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;
import javax.swing.JComponent;

/** Scopes × activities. Color is the effect; a dot means an attempt is required; outline = edited. */
final class PolicyMatrix extends JComponent {
  private static final int LABEL = 168;
  private static final int HEADER = 30;
  private static final int ROW = 40;
  Policy policy;
  Policy original;
  String scope = "";
  String activity = "";
  BiConsumer<String, String> onSelect = (scope, activity) -> {};
  private int hoverRow = -1;
  private int hoverColumn = -1;

  PolicyMatrix() {
    setFont(Theme.ui(11.5f));
    var mouse = new MouseAdapter() {
      @Override
      public void mouseMoved(MouseEvent event) {
        var cell = cellAt(event.getX(), event.getY());
        hoverRow = cell[0];
        hoverColumn = cell[1];
        setCursor(Cursor.getPredefinedCursor(cell[0] >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        repaint();
      }

      @Override
      public void mouseExited(MouseEvent event) {
        hoverRow = -1;
        hoverColumn = -1;
        repaint();
      }

      @Override
      public void mouseClicked(MouseEvent event) {
        var cell = cellAt(event.getX(), event.getY());
        if (cell[0] < 0 || policy == null) return;
        scope = policy.scopes().get(cell[0]).id();
        activity = Policy.ACTIVITIES.get(cell[1]);
        repaint();
        onSelect.accept(scope, activity);
      }
    };
    addMouseListener(mouse);
    addMouseMotionListener(mouse);
  }

  void show(Policy policy, Policy original) {
    this.policy = policy;
    this.original = original;
    revalidate();
    repaint();
  }

  private int[] cellAt(int x, int y) {
    if (policy == null || x < LABEL || y < HEADER) return new int[] {-1, -1};
    var column = (x - LABEL) * Policy.ACTIVITIES.size() / Math.max(1, getWidth() - LABEL);
    var row = (y - HEADER) / ROW;
    if (row >= policy.scopes().size() || column >= Policy.ACTIVITIES.size()) return new int[] {-1, -1};
    return new int[] {row, column};
  }

  @Override
  public Dimension getPreferredSize() {
    return new Dimension(LABEL + 10 * 64, HEADER + (policy == null ? 1 : policy.scopes().size()) * ROW + 2);
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    var g = Ui.smooth(graphics);
    if (policy == null) {
      g.setColor(Theme.p.faint);
      g.setFont(Theme.ui(12.5f));
      g.drawString("No policy loaded yet.", 4, 20);
      g.dispose();
      return;
    }
    var columns = Policy.ACTIVITIES.size();
    var cellWidth = (getWidth() - LABEL) / (double) columns;
    g.setFont(Theme.ui(11f, Font.BOLD));
    for (int column = 0; column < columns; column++) {
      var name = Ui.SHORT.get(Policy.ACTIVITIES.get(column));
      var metrics = g.getFontMetrics();
      g.setColor(Theme.p.faint);
      var x = (int) (LABEL + column * cellWidth + (cellWidth - metrics.stringWidth(name)) / 2);
      g.drawString(name, x, 18);
    }
    for (int row = 0; row < policy.scopes().size(); row++) {
      var scopeRow = policy.scopes().get(row);
      var y = HEADER + row * ROW;
      g.setFont(Theme.ui(12.5f, Font.BOLD));
      g.setColor(Theme.p.text);
      var name = scopeRow.id().equals("*") ? "Baseline *" : scopeRow.id();
      g.drawString(name, 4, y + 17);
      g.setFont(Theme.ui(10.5f));
      g.setColor(Theme.p.faint);
      var title = scopeRow.title();
      var metrics = g.getFontMetrics();
      while (metrics.stringWidth(title) > LABEL - 16 && title.length() > 4) title = title.substring(0, title.length() - 2);
      if (!title.equals(scopeRow.title())) title = title.strip() + "…";
      g.drawString(title, 4, y + 31);
      for (int column = 0; column < columns; column++) {
        var activityName = Policy.ACTIVITIES.get(column);
        var cell = PolicyReview.cell(policy, scopeRow.id(), activityName);
        var tone = Theme.effect(cell.effect());
        var x = (int) (LABEL + column * cellWidth) + 3;
        var w = (int) cellWidth - 6;
        var hovered = row == hoverRow && column == hoverColumn;
        g.setColor(Theme.alpha(tone, hovered ? (Theme.p.dark ? 70 : 60) : (Theme.p.dark ? 40 : 34)));
        g.fillRoundRect(x, y + 4, w, ROW - 8, 9, 9);
        g.setFont(Theme.ui(11f, Font.BOLD));
        g.setColor(tone);
        var label = cell.effect().equals("clarify") ? "ask" : cell.effect();
        var m = g.getFontMetrics();
        g.drawString(label, x + (w - m.stringWidth(label)) / 2, y + ROW / 2 + m.getAscent() / 2 - 1);
        if (cell.requireAttempt()) {
          g.setColor(Theme.p.clarify);
          g.fillOval(x + w - 9, y + 8, 5, 5);
        }
        var edited = original != null && original != policy
            && (!PolicyReview.cell(original, scopeRow.id(), activityName).effect().equals(cell.effect())
                || PolicyReview.cell(original, scopeRow.id(), activityName).requireAttempt() != cell.requireAttempt());
        var selected = scopeRow.id().equals(scope) && activityName.equals(activity);
        if (edited || selected) {
          g.setStroke(new BasicStroke(selected ? 2f : 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, edited && !selected ? new float[] {3f, 3f} : null, 0f));
          g.setColor(selected ? Theme.p.accent : Theme.p.text);
          g.drawRoundRect(x, y + 4, w, ROW - 8, 9, 9);
          g.setStroke(new BasicStroke(1f));
        }
      }
    }
    g.dispose();
  }
}

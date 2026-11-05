package edu.heelcode.policy.gui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * The HeelCode terminal wordmark rendered as pixels: the same "HEEL" + "CODE" block glyphs as
 * packages/tui/src/logo.ts, the same UNC Navy → Carolina Blue row gradients, and the same
 * ANSI-style shadow layer offset one cell down and right. A light sweep crosses it now and then,
 * like the TUI's idle shimmer, and on hover.
 */
final class PixelMark extends JComponent {
  private static final String[] LEFT = {
    "██   ██ ███████ ███████ ██     ",
    "██   ██ ██      ██      ██     ",
    "██   ██ ██      ██      ██     ",
    "███████ ██████  ██████  ██     ",
    "██   ██ ██      ██      ██     ",
    "██   ██ ██      ██      ██     ",
    "██   ██ ███████ ███████ ███████",
  };
  private static final String[] RIGHT = {
    " ██████  █████  ██████  ███████",
    "██      ██   ██ ██   ██ ██     ",
    "██      ██   ██ ██   ██ ██     ",
    "██      ██   ██ ██   ██ ██████ ",
    "██      ██   ██ ██   ██ ██     ",
    "██      ██   ██ ██   ██ ██     ",
    " ██████  █████  ██████  ███████",
  };
  private static final int GAP = 2;
  private static final Color NAVY = new Color(19, 41, 75);
  private static final Color BLUE = new Color(75, 156, 211);
  private static final Color LIGHT = new Color(166, 216, 248);
  private static final int SWEEP_MS = 1100;

  private final double cell;
  private final Timer timer;
  private long sweepStarted = -1;
  private long nextSweep;
  private Color background;

  /** {@code cell} is the width of one terminal column; rows are twice as tall, like a terminal. */
  PixelMark(double cell, Color background) {
    this.cell = cell;
    this.background = background;
    var columns = LEFT[0].length() + GAP + RIGHT[0].length() + 1;
    setPreferredSize(new Dimension((int) Math.ceil(columns * cell), (int) Math.ceil((LEFT.length + 1) * cell * 2)));
    setOpaque(false);
    nextSweep = System.currentTimeMillis() + 1800;
    timer = new Timer(33, event -> tick());
  }

  void background(Color background) {
    this.background = background;
    repaint();
  }

  void sweep() {
    if (sweepStarted < 0) sweepStarted = System.currentTimeMillis();
  }

  private void tick() {
    var now = System.currentTimeMillis();
    if (sweepStarted < 0 && now >= nextSweep) sweepStarted = now;
    if (sweepStarted >= 0 && now - sweepStarted > SWEEP_MS) {
      sweepStarted = -1;
      nextSweep = now + 9000 + (long) (Math.random() * 6000);
    }
    if (sweepStarted >= 0) repaint();
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

  @Override
  protected void paintComponent(Graphics graphics) {
    var g = (Graphics2D) graphics.create();
    var rows = LEFT.length;
    var leftWidth = LEFT[0].length();
    var total = leftWidth + GAP + RIGHT[0].length();
    var progress = sweepStarted < 0 ? -1 : (System.currentTimeMillis() - sweepStarted) / (double) SWEEP_MS;
    // Shadow first, one cell down-right, so only the bottom and right edges peek out.
    for (var layer = 0; layer < 2; layer++) {
      for (int row = 0; row < rows; row++) {
        for (int column = 0; column < total; column++) {
          var lit = lit(row, column, leftWidth);
          if (!lit) continue;
          var ink = ink(row, column < leftWidth, rows);
          if (layer == 0) {
            if (lit(row + 1, column + 1, leftWidth)) continue;
            g.setColor(Theme.mix(background, ink, Theme.p.dark ? 0.24 : 0.22));
            fill(g, column + 1, row + 1);
          } else {
            g.setColor(progress < 0 ? ink : Theme.mix(ink, Color.WHITE, glow(progress, row, column, total, rows)));
            fill(g, column, row);
          }
        }
      }
    }
    g.dispose();
  }

  private static boolean lit(int row, int column, int leftWidth) {
    if (row < 0 || row >= LEFT.length) return false;
    if (column < leftWidth) return column < LEFT[row].length() && LEFT[row].charAt(column) != ' ';
    var right = column - leftWidth - GAP;
    return right >= 0 && right < RIGHT[row].length() && RIGHT[row].charAt(right) != ' ';
  }

  /** Same row gradients as the TUI: HEEL navy→Carolina, CODE Carolina→light Carolina. */
  private Color ink(int row, boolean left, int rows) {
    var depth = rows <= 1 ? 0 : row / (double) (rows - 1);
    if (Theme.p.dark) {
      var color = left ? Theme.mix(NAVY, BLUE, 0.42 + depth * 0.3) : Theme.mix(BLUE, LIGHT, 0.16 + depth * 0.34);
      return Theme.mix(background, color, left ? 0.95 : 0.97);
    }
    return left ? Theme.mix(NAVY, BLUE, 0.05 + depth * 0.2) : Theme.mix(BLUE, NAVY, 0.12 - depth * 0.1);
  }

  /** A soft diagonal band that crosses the mark left to right. */
  private static double glow(double progress, int row, int column, int columns, int rows) {
    var position = progress * (columns + rows * 2 + 16) - 8;
    var distance = Math.abs(column + (rows - row) * 1.2 - position);
    return distance > 7 ? 0 : 0.55 * (1 - distance / 7.0);
  }

  /** Unrounded cells: at 1.5pt and 2x Retina each terminal column is exactly 3 device pixels. */
  private void fill(Graphics2D g, int column, int row) {
    g.fill(new java.awt.geom.Rectangle2D.Double(column * cell, row * cell * 2, cell, cell * 2));
  }
}

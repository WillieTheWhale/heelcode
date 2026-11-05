package edu.heelcode.policy.gui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;

/** An original argyle mark: a Carolina Blue diamond with a white overcheck on UNC Navy. */
final class Brand {
  static void paint(Graphics2D g, double x, double y, double size) {
    var s = size;
    g.setPaint(new GradientPaint((float) x, (float) y, new Color(0x1B3A66), (float) x, (float) (y + s), Theme.NAVY));
    g.fill(new RoundRectangle2D.Double(x, y, s, s, s * 0.46, s * 0.46));
    var diamond = new Path2D.Double();
    diamond.moveTo(x + s * 0.5, y + s * 0.17);
    diamond.lineTo(x + s * 0.83, y + s * 0.5);
    diamond.lineTo(x + s * 0.5, y + s * 0.83);
    diamond.lineTo(x + s * 0.17, y + s * 0.5);
    diamond.closePath();
    g.setPaint(new GradientPaint((float) x, (float) y, Theme.CAROLINA_LIGHT, (float) x, (float) (y + s), Theme.CAROLINA));
    g.fill(diamond);
    var stroke = (float) Math.max(1.0, s * 0.045);
    g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {stroke * 1.6f, stroke * 1.9f}, 0f));
    g.setColor(new Color(255, 255, 255, 215));
    var clip = g.getClip();
    g.clip(diamond);
    g.draw(new java.awt.geom.Line2D.Double(x + s * 0.17, y + s * 0.17, x + s * 0.83, y + s * 0.83));
    g.draw(new java.awt.geom.Line2D.Double(x + s * 0.83, y + s * 0.17, x + s * 0.17, y + s * 0.83));
    g.setClip(clip);
  }

  static JComponent mark(int size) {
    var component =
        new JComponent() {
          @Override
          protected void paintComponent(Graphics graphics) {
            var g = Ui.smooth(graphics);
            Brand.paint(g, 0, 0, Math.min(getWidth(), getHeight()));
            g.dispose();
          }
        };
    component.setPreferredSize(new Dimension(size, size));
    component.setMinimumSize(new Dimension(size, size));
    component.setMaximumSize(new Dimension(size, size));
    return component;
  }

  static Image icon(int size) {
    var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    var g = Ui.smooth(image.getGraphics());
    var inset = size * 0.1;
    paint(g, inset, inset, size - inset * 2);
    g.dispose();
    return image;
  }

  /** A faint argyle field that fades out from the center; drawn behind the empty state. */
  static void argyle(Graphics2D g, int width, int height, int centerY) {
    var tile = 46;
    var alpha = Theme.p.dark ? 0.06f : 0.07f;
    for (int row = -1; row * tile / 2 < height + tile; row++) {
      for (int column = -1; column * tile < width + tile; column++) {
        var cx = column * tile + (row % 2 == 0 ? 0 : tile / 2);
        var cy = row * tile / 2;
        var distance = Math.hypot((cx - width / 2.0) / (width * 0.42), (cy - centerY) / (height * 0.36));
        var fade = (float) Math.max(0, 1 - distance);
        if (fade <= 0) continue;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha * fade));
        var diamond = new Path2D.Double();
        diamond.moveTo(cx, cy - tile / 2.0 + 3);
        diamond.lineTo(cx + tile / 2.0 - 3, cy);
        diamond.lineTo(cx, cy + tile / 2.0 - 3);
        diamond.lineTo(cx - tile / 2.0 + 3, cy);
        diamond.closePath();
        g.setColor(Theme.p.accent);
        g.fill(diamond);
      }
    }
    g.setComposite(AlphaComposite.SrcOver);
  }
}

package edu.heelcode.policy.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.text.JTextComponent;

/** Small shared building blocks so each view reads as layout, not widget plumbing. */
final class Ui {
  static final ExecutorService WORK =
      Executors.newCachedThreadPool(
          task -> {
            var thread = new Thread(task, "heelcode-gui-work");
            thread.setDaemon(true);
            return thread;
          });

  static final Map<String, String> ACTIVITY =
      Map.of(
          "concept", "Concept explanation",
          "hint", "Conceptual hint",
          "debug", "Debugging help",
          "test_design", "Test design ideas",
          "test_code", "Runnable tests",
          "pseudocode", "Solution pseudocode",
          "implementation", "Solution code",
          "full_solution", "Complete solution",
          "writeup", "Submission-ready writing",
          "logistics", "Course logistics");

  static final Map<String, String> SHORT =
      Map.of(
          "concept", "Concept",
          "hint", "Hint",
          "debug", "Debug",
          "test_design", "Test ideas",
          "test_code", "Tests",
          "pseudocode", "Pseudo",
          "implementation", "Code",
          "full_solution", "Full",
          "writeup", "Writeup",
          "logistics", "Logistics");

  // ---------------------------------------------------------------- icons and text

  static FlatSVGIcon icon(String name, int size, Color color) {
    var icon =
        new FlatSVGIcon(
            "edu/heelcode/policy/gui/icons/" + name + ".svg", size, size, Ui.class.getClassLoader());
    icon.setColorFilter(new FlatSVGIcon.ColorFilter(ignored -> color));
    return icon;
  }

  static JLabel label(String text, float size, int style, Color color) {
    var label = new JLabel(text);
    label.setFont(Theme.ui(size, style));
    label.setForeground(color);
    return label;
  }

  static JLabel muted(String text) {
    return label(text, 12f, Font.PLAIN, Theme.p.muted);
  }

  /** Read-only wrapping text that participates in width-dependent layout. */
  static JTextArea wrap(String text, float size, Color color) {
    var area = new JTextArea(text);
    area.setFont(Theme.ui(size));
    area.setForeground(color);
    area.setLineWrap(true);
    area.setWrapStyleWord(true);
    area.setEditable(false);
    area.setFocusable(false);
    area.setOpaque(false);
    area.setBorder(null);
    area.setHighlighter(null);
    return area;
  }

  /** Centered, wrapping, read-only paragraph. */
  static javax.swing.JTextPane centered(String text, float size, Color color) {
    var pane = new javax.swing.JTextPane();
    pane.setFont(Theme.ui(size));
    pane.setForeground(color);
    pane.setText(text);
    var document = pane.getStyledDocument();
    var center = new javax.swing.text.SimpleAttributeSet();
    javax.swing.text.StyleConstants.setAlignment(center, javax.swing.text.StyleConstants.ALIGN_CENTER);
    document.setParagraphAttributes(0, document.getLength(), center, false);
    pane.setEditable(false);
    pane.setFocusable(false);
    pane.setOpaque(false);
    pane.setBorder(null);
    pane.setHighlighter(null);
    return pane;
  }

  static JTextArea selectable(String text, Font font, Color color) {
    var area = wrap(text, 13f, color);
    area.setFont(font);
    area.setFocusable(true);
    area.setHighlighter(new javax.swing.text.DefaultHighlighter());
    return area;
  }

  // ---------------------------------------------------------------- buttons

  static JButton primary(String text, String icon) {
    var button = new JButton(text);
    if (icon != null) button.setIcon(icon(icon, 15, Theme.p.primaryText));
    style(
        button,
        "arc: 10; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; margin: 6,14,6,14; font: bold;"
            + " background: "
            + Theme.hex(Theme.p.primary)
            + "; foreground: "
            + Theme.hex(Theme.p.primaryText)
            + "; hoverBackground: "
            + Theme.hex(Theme.mix(Theme.p.primary, Color.WHITE, Theme.p.dark ? 0.15 : 0.12))
            + "; pressedBackground: "
            + Theme.hex(Theme.mix(Theme.p.primary, Color.BLACK, 0.12))
            + "; disabledBackground: "
            + Theme.hex(Theme.p.hover)
            + "; disabledText: "
            + Theme.hex(Theme.p.faint));
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    return button;
  }

  static JButton secondary(String text, String icon) {
    var button = new JButton(text);
    if (icon != null) button.setIcon(icon(icon, 15, Theme.p.text));
    style(
        button,
        "arc: 10; borderWidth: 1; focusWidth: 0; innerFocusWidth: 0; margin: 6,12,6,12;"
            + " borderColor: "
            + Theme.hex(Theme.p.borderStrong)
            + "; background: "
            + Theme.hex(Theme.p.surface)
            + "; foreground: "
            + Theme.hex(Theme.p.text)
            + "; hoverBackground: "
            + Theme.hex(Theme.p.hover)
            + "; pressedBackground: "
            + Theme.hex(Theme.p.selected));
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    return button;
  }

  static JButton ghost(String text, String icon) {
    var button = new JButton(text);
    if (icon != null) button.setIcon(icon(icon, 15, Theme.p.muted));
    button.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
    style(
        button,
        "arc: 8; margin: 4,8,4,8; foreground: "
            + Theme.hex(Theme.p.muted)
            + "; hoverBackground: "
            + Theme.hex(Theme.p.hover)
            + "; pressedBackground: "
            + Theme.hex(Theme.p.selected));
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    return button;
  }

  /** A rounded selector with a trailing chevron, like the Codex composer controls. */
  static JButton pill(String text, String icon) {
    var button =
        new JButton(text) {
          @Override
          protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            var g = smooth(graphics);
            g.setColor(Theme.p.faint);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            var x = getWidth() - 15;
            var y = getHeight() / 2 - 1;
            g.drawLine(x, y, x + 3, y + 3);
            g.drawLine(x + 3, y + 3, x + 6, y);
            g.dispose();
          }
        };
    if (icon != null) button.setIcon(icon(icon, 14, Theme.p.muted));
    button.setIconTextGap(6);
    style(
        button,
        "arc: 999; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; margin: 3,9,3,22; font: -1;"
            + " background: "
            + Theme.hex(Theme.p.dark ? Theme.p.raised : Theme.p.hover)
            + "; foreground: "
            + Theme.hex(Theme.p.muted)
            + "; hoverBackground: "
            + Theme.hex(Theme.p.dark ? Theme.p.hover : Theme.p.selected)
            + "; pressedBackground: "
            + Theme.hex(Theme.p.selected));
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    return button;
  }

  static void style(JComponent component, String style) {
    component.putClientProperty(FlatClientProperties.STYLE, style);
  }

  // ---------------------------------------------------------------- containers

  /** Rounded surface with a hairline border. */
  static class Card extends JPanel {
    Color fill;
    Color stroke;
    int arc = 14;

    Card() {
      this(Theme.p.surface, Theme.p.border);
    }

    Card(Color fill, Color stroke) {
      this.fill = fill;
      this.stroke = stroke;
      setOpaque(false);
      setBorder(new EmptyBorder(14, 16, 14, 16));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = smooth(graphics);
      if (fill != null) {
        g.setColor(fill);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
      }
      if (stroke != null) {
        g.setColor(stroke);
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
      }
      g.dispose();
    }
  }

  /** Width-dependent height, so wrapped text can be laid out without a fixed size. */
  interface Flow {
    int heightFor(int width);
  }

  /** A component that is narrower than its row (e.g. a right-aligned message bubble). */
  interface Sized {
    int widthFor(int available);
  }

  static int heightFor(Component component, int width) {
    if (component instanceof Flow flow) return flow.heightFor(width);
    if (component instanceof JTextComponent text) {
      text.setSize(Math.max(1, width), Short.MAX_VALUE);
      return text.getPreferredSize().height;
    }
    return component.getPreferredSize().height;
  }

  enum Align {
    FILL,
    LEFT,
    CENTER,
    RIGHT
  }

  /** Vertical stack: children fill (or align within) a centered column of at most maxWidth. */
  static class Stack extends JPanel implements Flow, Scrollable {
    int gap;
    int maxWidth;
    Color fill;
    Color stroke;
    Color accent;
    int arc = 14;

    Stack(int gap) {
      super(null);
      this.gap = gap;
      setOpaque(false);
    }

    /** Paints this stack as a rounded card. */
    Stack card(Color fill, Color stroke) {
      this.fill = fill;
      this.stroke = stroke;
      return this;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      if (fill == null && stroke == null && accent == null) return;
      var g = smooth(graphics);
      if (fill != null) {
        g.setColor(fill);
        g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
      }
      if (accent != null) {
        g.setColor(accent);
        g.fillRoundRect(0, 0, 4, getHeight() - 1, 4, 4);
      }
      if (stroke != null) {
        g.setColor(stroke);
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
      }
      g.dispose();
    }

    Stack add(Component component, Align align) {
      if (component instanceof JComponent child) child.putClientProperty("heelcode.align", align);
      add(component);
      return this;
    }

    private Align align(Component component) {
      return component instanceof JComponent child
              && child.getClientProperty("heelcode.align") instanceof Align align
          ? align
          : Align.FILL;
    }

    private int gapAfter(Component component) {
      return component instanceof JComponent child
              && child.getClientProperty("heelcode.gap") instanceof Integer value
          ? value
          : gap;
    }

    private int column(int width) {
      var insets = getInsets();
      var inner = Math.max(0, width - insets.left - insets.right);
      return maxWidth > 0 ? Math.min(inner, maxWidth) : inner;
    }

    private int childWidth(Component component, int column) {
      var align = align(component);
      if (align == Align.FILL) return column;
      if (component instanceof Sized sized) return Math.min(column, sized.widthFor(column));
      return Math.min(column, component.getPreferredSize().width);
    }

    @Override
    public void doLayout() {
      var insets = getInsets();
      var column = column(getWidth());
      var left = insets.left + (getWidth() - insets.left - insets.right - column) / 2;
      var y = insets.top;
      for (var component : getComponents()) {
        if (!component.isVisible()) continue;
        var width = childWidth(component, column);
        var x =
            switch (align(component)) {
              case RIGHT -> left + column - width;
              case CENTER -> left + (column - width) / 2;
              default -> left;
            };
        var height = Ui.heightFor(component, width);
        component.setBounds(x, y, width, height);
        y += height + gapAfter(component);
      }
    }

    @Override
    public int heightFor(int width) {
      var insets = getInsets();
      var column = column(width);
      var height = insets.top + insets.bottom;
      var visible = 0;
      for (var component : getComponents()) {
        if (!component.isVisible()) continue;
        height += Ui.heightFor(component, childWidth(component, column)) + gapAfter(component);
        visible++;
      }
      if (visible > 0) height -= gapAfter(getComponent(getComponentCount() - 1));
      return height;
    }

    @Override
    public Dimension getPreferredSize() {
      if (isPreferredSizeSet()) return super.getPreferredSize();
      var width = getWidth() > 0 ? getWidth() : (getParent() != null ? getParent().getWidth() : 0);
      if (width <= 0) width = maxWidth > 0 ? maxWidth : 480;
      return new Dimension(maxWidth > 0 ? Math.min(width, maxWidth + getInsets().left + getInsets().right) : width, heightFor(width));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
      return 18;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
      return Math.max(18, visible.height - 40);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
      return false;
    }
  }

  static <T extends JComponent> T gap(T component, int after) {
    component.putClientProperty("heelcode.gap", after);
    return component;
  }

  static JScrollPane scroll(Component view) {
    var pane = new JScrollPane(view);
    pane.setBorder(BorderFactory.createEmptyBorder());
    pane.setViewportBorder(null);
    pane.getViewport().setOpaque(false);
    pane.setOpaque(false);
    pane.getVerticalScrollBar().setUnitIncrement(18);
    pane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    return pane;
  }

  static JPanel row(int gap, Component... components) {
    var panel = new JPanel(new FlowLayout(FlowLayout.LEFT, gap, 0));
    panel.setOpaque(false);
    for (var component : components) panel.add(component);
    return panel;
  }

  /** Left-to-right wrapping row of chips; reports the wrapped height for its width. */
  static class Wrap extends JPanel implements Flow {
    final int hgap;
    final int vgap;

    Wrap(int hgap, int vgap) {
      super(null);
      this.hgap = hgap;
      this.vgap = vgap;
      setOpaque(false);
    }

    @Override
    public void doLayout() {
      place(getWidth(), true);
    }

    @Override
    public int heightFor(int width) {
      return place(width, false);
    }

    private int place(int width, boolean apply) {
      var x = 0;
      var y = 0;
      var line = 0;
      for (var component : getComponents()) {
        var size = component.getPreferredSize();
        if (x > 0 && x + size.width > width) {
          x = 0;
          y += line + vgap;
          line = 0;
        }
        if (apply) component.setBounds(x, y, Math.min(size.width, width), size.height);
        x += size.width + hgap;
        line = Math.max(line, size.height);
      }
      return getComponentCount() == 0 ? 0 : y + line;
    }

    @Override
    public Dimension getPreferredSize() {
      var width = getWidth() > 0 ? getWidth() : 600;
      return new Dimension(width, heightFor(width));
    }
  }

  /** A leading icon beside wrapping text, e.g. a quoted policy passage. */
  static class IconText extends JPanel implements Flow {
    private final JLabel glyph;
    private final JTextArea text;

    IconText(String icon, String message, float size, Color color) {
      super(null);
      setOpaque(false);
      glyph = iconLabel(icon, 13, color);
      text = wrap(message, size, color);
      add(glyph);
      add(text);
    }

    @Override
    public int heightFor(int width) {
      return Math.max(18, Ui.heightFor(text, Math.max(40, width - 22)));
    }

    @Override
    public void doLayout() {
      glyph.setBounds(0, 1, 16, 16);
      text.setBounds(22, 0, Math.max(40, getWidth() - 22), heightFor(getWidth()));
    }

    @Override
    public Dimension getPreferredSize() {
      var width = getWidth() > 0 ? getWidth() : 400;
      return new Dimension(width, heightFor(width));
    }
  }

  // ---------------------------------------------------------------- small visuals

  static class Chip extends JComponent {
    final String text;
    final Color foreground;
    final Color background;
    final Icon icon;

    Chip(String text, Color foreground, Color background, Icon icon) {
      this.text = text;
      this.foreground = foreground;
      this.background = background;
      this.icon = icon;
      setFont(Theme.ui(11.5f, Font.PLAIN));
    }

    static Chip of(String text, Color tone) {
      return new Chip(text, tone, Theme.alpha(tone, Theme.p.dark ? 34 : 26), null);
    }

    static Chip neutral(String text) {
      return new Chip(text, Theme.p.muted, Theme.p.dark ? Theme.p.raised : Theme.p.hover, null);
    }

    @Override
    public Dimension getPreferredSize() {
      var metrics = getFontMetrics(getFont());
      var width = metrics.stringWidth(text) + 18 + (icon == null ? 0 : icon.getIconWidth() + 5);
      return new Dimension(width, 22);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = smooth(graphics);
      g.setColor(background);
      g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
      var x = 9;
      if (icon != null) {
        icon.paintIcon(this, g, x, (getHeight() - icon.getIconHeight()) / 2);
        x += icon.getIconWidth() + 5;
      }
      g.setFont(getFont());
      g.setColor(foreground);
      var metrics = g.getFontMetrics();
      g.drawString(text, x, (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
      g.dispose();
    }
  }

  /** Allow / deny / clarify badge in the decision colors. */
  static Chip decision(String action) {
    var tone = Theme.effect(action);
    var label =
        switch (action) {
          case "allow" -> "Allowed";
          case "deny" -> "Declined";
          default -> "Needs clarification";
        };
    var icon =
        switch (action) {
          case "allow" -> "check";
          case "deny" -> "ban";
          default -> "help";
        };
    return new Chip(label, tone, Theme.alpha(tone, Theme.p.dark ? 34 : 24), icon(icon, 12, tone));
  }

  static class Spinner extends JComponent {
    private final Timer timer;
    private int angle;

    Spinner(int size) {
      setPreferredSize(new Dimension(size, size));
      timer =
          new Timer(
              16,
              event -> {
                angle = (angle + 8) % 360;
                repaint();
              });
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
      var g = smooth(graphics);
      var size = Math.min(getWidth(), getHeight()) - 3;
      g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g.setColor(Theme.alpha(Theme.p.accent, 60));
      g.drawOval(1, 1, size, size);
      g.setColor(Theme.p.accent);
      g.drawArc(1, 1, size, size, -angle, 100);
      g.dispose();
    }
  }

  static JComponent divider() {
    var line =
        new JComponent() {
          @Override
          protected void paintComponent(Graphics g) {
            g.setColor(Theme.p.border);
            g.fillRect(0, getHeight() / 2, getWidth(), 1);
          }
        };
    line.setPreferredSize(new Dimension(1, 9));
    return line;
  }

  static Graphics2D smooth(Graphics graphics) {
    var g = (Graphics2D) graphics.create();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    return g;
  }

  static void hover(JComponent component, Runnable enter, Runnable exit) {
    component.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseEntered(MouseEvent event) {
            enter.run();
          }

          @Override
          public void mouseExited(MouseEvent event) {
            exit.run();
          }
        });
  }

  static void click(JComponent component, Runnable action) {
    component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    component.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mouseClicked(MouseEvent event) {
            if (SwingUtilities.isLeftMouseButton(event)) action.run();
          }
        });
  }

  static JLabel section(String text) {
    var label = label(text.toUpperCase(), 10.5f, Font.BOLD, Theme.p.faint);
    label.setBorder(new EmptyBorder(0, 2, 0, 0));
    return label;
  }

  static JLabel iconLabel(String icon, int size, Color color) {
    var label = new JLabel(icon(icon, size, color));
    label.setHorizontalAlignment(SwingConstants.CENTER);
    return label;
  }

  static EmptyBorder pad(int top, int left, int bottom, int right) {
    return new EmptyBorder(new Insets(top, left, bottom, right));
  }

  // ---------------------------------------------------------------- async and formatting

  static <T> void async(Callable<T> task, Consumer<T> done, Consumer<Exception> failed) {
    WORK.submit(
        () -> {
          try {
            var value = task.call();
            SwingUtilities.invokeLater(() -> done.accept(value));
          } catch (Exception error) {
            SwingUtilities.invokeLater(() -> failed.accept(error));
          }
        });
  }

  static String message(Throwable error) {
    var cause = error;
    while (cause.getCause() != null && cause.getMessage() == null) cause = cause.getCause();
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }

  static String ago(Instant instant) {
    var seconds = Duration.between(instant, Instant.now()).getSeconds();
    if (seconds < 60) return "now";
    if (seconds < 3600) return seconds / 60 + "m";
    if (seconds < 86_400) return seconds / 3600 + "h";
    return seconds / 86_400 + "d";
  }

  static String seconds(long millis) {
    return String.format("%.1fs", millis / 1000.0);
  }

  static String shortSha(String sha) {
    return sha == null || sha.length() < 8 ? "—" : sha.substring(0, 8);
  }

  static String join(List<String> activities) {
    return String.join(", ", activities.stream().map(a -> ACTIVITY.getOrDefault(a, a).toLowerCase()).toList());
  }
}

package edu.heelcode.policy.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Codex's composer: a 24px-radius well on the elevated gray with a 14px input, then a control row
 * — "+" on the left beside the permission indicator (here the policy gate, in Carolina instead of
 * Codex's orange "Full access"), the model selector and a round send button on the right. A new
 * chat adds a tray of course and assignment selectors along the top, like Codex's project tray.
 */
final class Composer extends JPanel {
  static final String PLACEHOLDER = "Ask HeelCode";
  private static final int ARC = 48;
  final JTextArea input =
      new JTextArea(1, 20) {
        @Override
        protected void paintComponent(Graphics graphics) {
          super.paintComponent(graphics);
          if (!getText().isEmpty()) return;
          var g = Ui.smooth(graphics);
          g.setFont(getFont());
          g.setColor(Theme.p.faint);
          var insets = getInsets();
          g.drawString(placeholder, insets.left, insets.top + g.getFontMetrics().getAscent());
          g.dispose();
        }
      };
  final JButton plus = MainFrame.toolbarIcon("plus", "Ask for a kind of help");
  final Selector policy = new Selector("shield", "Policy", null, true);
  final Selector model = new Selector(null, "Luna", "answers", true);
  final Selector course = new Selector("folder", "Course", null, true);
  final Selector assignment = new Selector("book", "Assignment", null, true);
  final JButton settings = MainFrame.toolbarIcon("sliders", "Admin prompts");
  private final JPanel tray = new JPanel(new BorderLayout());
  private final Send send = new Send();
  Consumer<String> onSend = text -> {};
  String placeholder = PLACEHOLDER;
  private boolean busy;

  Composer() {
    super(new BorderLayout());
    setOpaque(false);
    input.setLineWrap(true);
    input.setWrapStyleWord(true);
    input.setFont(Theme.ui(14.5f));
    input.setForeground(Theme.p.text);
    input.setCaretColor(Theme.p.text);
    input.setOpaque(false);
    input.setBorder(Ui.pad(0, 0, 0, 0));
    input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
    input.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK), "insert-break");
    input.getActionMap().put("send", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent event) {
        submit();
      }
    });
    input.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent event) { grow(); }
      public void removeUpdate(DocumentEvent event) { grow(); }
      public void changedUpdate(DocumentEvent event) { grow(); }
    });
    var scroll =
        new JScrollPane(input) {
          @Override
          public Dimension getPreferredSize() {
            var width = Math.max(200, getWidth() > 0 ? getWidth() : 600);
            var insets = getInsets();
            var line = input.getFontMetrics(input.getFont()).getHeight();
            var text = Ui.heightFor(input, Math.max(40, width - insets.left - insets.right - 10));
            return new Dimension(width, insets.top + insets.bottom + Math.min(line * 9, Math.max(line * 2, text)));
          }
        };
    scroll.setBorder(Ui.pad(14, 16, 4, 14));
    scroll.getVerticalScrollBar().setPreferredSize(new Dimension(6, 10));
    scroll.setOpaque(false);
    scroll.getViewport().setOpaque(false);
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    add(scroll, BorderLayout.CENTER);

    tray.setOpaque(false);
    tray.setBorder(Ui.pad(6, 8, 6, 8));
    var trayLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    trayLeft.setOpaque(false);
    trayLeft.add(course);
    trayLeft.add(assignment);
    tray.add(trayLeft, BorderLayout.WEST);
    tray.add(settings, BorderLayout.EAST);
    tray.setVisible(false);
    add(tray, BorderLayout.NORTH);

    var controls = new JPanel(new BorderLayout());
    controls.setOpaque(false);
    controls.setBorder(Ui.pad(0, 7, 8, 8));
    var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    left.setOpaque(false);
    left.add(plus);
    left.add(policy);
    controls.add(left, BorderLayout.WEST);
    var right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    right.setOpaque(false);
    right.add(model);
    send.addActionListener(event -> submit());
    right.add(send);
    controls.add(right, BorderLayout.EAST);
    add(controls, BorderLayout.SOUTH);
  }

  /** Shows the course/assignment tray (new chats only: a chat is pinned to one assignment). */
  void tray(boolean visible) {
    tray.setVisible(visible);
    revalidate();
    repaint();
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    var g = Ui.smooth(graphics);
    var height = getHeight() - 4;
    if (!Theme.p.dark) {
      for (int i = 3; i >= 1; i--) {
        g.setColor(new Color(0, 0, 0, 4 * i));
        g.fillRoundRect(0, i, getWidth() - 1, height, ARC, ARC);
      }
    }
    g.setColor(Theme.p.composer);
    g.fillRoundRect(0, 0, getWidth() - 1, height, ARC, ARC);
    if (tray.isVisible()) {
      // The tray reads as the composer's lid: a slightly recessed band over a hairline.
      var band = tray.getHeight();
      var clip = g.getClip();
      g.clipRect(0, 0, getWidth(), band);
      g.setColor(Theme.p.dark ? Theme.mix(Theme.p.composer, Color.BLACK, 0.16) : new Color(0xF7F7F7));
      g.fillRoundRect(0, 0, getWidth() - 1, height, ARC, ARC);
      g.setClip(clip);
      g.setColor(Theme.p.dark ? Theme.alpha(Color.WHITE, 12) : Theme.p.subtle);
      g.fillRect(1, band, getWidth() - 3, 1);
    }
    g.setColor(Theme.p.dark ? Theme.alpha(Color.WHITE, 9) : Theme.p.border);
    g.drawRoundRect(0, 0, getWidth() - 1, height, ARC, ARC);
    g.dispose();
  }

  @Override
  public java.awt.Insets getInsets() {
    var insets = super.getInsets();
    return new java.awt.Insets(insets.top, insets.left, insets.bottom + 4, insets.right);
  }

  private void grow() {
    send.repaint();
    revalidate();
    if (getParent() != null) getParent().revalidate();
  }

  private void submit() {
    var text = input.getText().strip();
    if (busy || text.isEmpty()) return;
    onSend.accept(text);
  }

  void setBusy(boolean busy) {
    this.busy = busy;
    for (var control : new JComponent[] {plus, policy, model, course, assignment}) control.setEnabled(!busy);
    send.repaint();
  }

  void clear() {
    input.setText("");
  }

  void prefill(String text) {
    input.setText(text);
    input.requestFocusInWindow();
    input.setCaretPosition(text.length());
  }

  /** Codex's composer selector: optional icon, a label, a fainter detail, and a chevron. */
  static final class Selector extends JComponent {
    private String icon;
    private String text;
    private String detail;
    private final boolean chevron;
    private Color tone;
    private boolean hover;
    private Runnable action = () -> {};

    Selector(String icon, String text, String detail, boolean chevron) {
      this.icon = icon;
      this.text = text;
      this.detail = detail;
      this.chevron = chevron;
      setFont(Theme.ui(13.5f));
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      Ui.hover(this, () -> { hover = true; repaint(); }, () -> { hover = false; repaint(); });
      Ui.click(this, () -> {
        if (isEnabled()) action.run();
      });
    }

    void onClick(Runnable action) {
      this.action = action;
    }

    void set(String icon, String text, String detail, Color tone) {
      this.icon = icon;
      this.text = text;
      this.detail = detail;
      this.tone = tone;
      revalidate();
      repaint();
    }

    @Override
    public Dimension getPreferredSize() {
      var metrics = getFontMetrics(getFont());
      var width = 16 + metrics.stringWidth(text);
      if (detail != null && !detail.isEmpty()) width += 5 + metrics.stringWidth(detail);
      if (icon != null) width += 20;
      if (chevron) width += 16;
      return new Dimension(width, 28);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      if (hover && isEnabled()) {
        g.setColor(Theme.p.hover);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
      }
      var color = !isEnabled() ? Theme.p.faint : tone != null ? tone : Theme.p.text;
      var x = 8;
      if (icon != null) {
        Icon glyph = Ui.icon(icon, 15, tone != null ? color : Theme.p.muted);
        glyph.paintIcon(this, g, x, (getHeight() - 15) / 2);
        x += 20;
      }
      g.setFont(getFont());
      var metrics = g.getFontMetrics();
      var y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
      g.setColor(color);
      g.drawString(text, x, y);
      x += metrics.stringWidth(text);
      if (detail != null && !detail.isEmpty()) {
        g.setColor(Theme.p.faint);
        g.drawString(detail, x + 5, y);
        x += 5 + metrics.stringWidth(detail);
      }
      if (chevron) Ui.icon("chevron-down", 13, Theme.p.faint).paintIcon(this, g, x + 3, (getHeight() - 13) / 2 + 1);
      g.dispose();
    }
  }

  /** A 28px circle: Carolina with a Navy arrow when there is something to send. */
  private final class Send extends JButton {
    Send() {
      setPreferredSize(new Dimension(28, 28));
      setBorderPainted(false);
      setContentAreaFilled(false);
      setFocusPainted(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setToolTipText("Send (Enter) · new line (Shift+Enter)");
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      var ready = !busy && !input.getText().isBlank();
      g.setColor(ready ? Theme.p.primary : Theme.p.hover);
      g.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
      var arrow = Ui.icon("arrow-up", 16, ready ? Theme.p.primaryText : Theme.p.faint);
      arrow.paintIcon(this, g, (getWidth() - 16) / 2, (getHeight() - 16) / 2);
      g.dispose();
    }
  }
}

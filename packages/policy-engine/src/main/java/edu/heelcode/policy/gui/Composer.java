package edu.heelcode.policy.gui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** The rounded prompt box: text, assignment/classifier/answer selectors, and a send button. */
final class Composer extends Ui.Card {
  static final String PLACEHOLDER = "Ask about your assignment — a concept, a hint, or a bug you're stuck on";
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
          g.drawString(PLACEHOLDER, insets.left, insets.top + g.getFontMetrics().getAscent());
          g.dispose();
        }
      };
  final JButton assignment = Ui.pill("Assignment", "book");
  final JButton classifier = Ui.pill("Classifier", "shield");
  final JButton answer = Ui.pill("Answers", "spark");
  private final Send send = new Send();
  private final JScrollPane scroll;
  Consumer<String> onSend = text -> {};
  private boolean busy;

  Composer() {
    super(Theme.p.surface, Theme.p.borderStrong);
    arc = 20;
    setLayout(new BorderLayout(0, 6));
    setBorder(Ui.pad(12, 16, 10, 10));
    input.setLineWrap(true);
    input.setWrapStyleWord(true);
    input.setFont(Theme.ui(14f));
    input.setForeground(Theme.p.text);
    input.setOpaque(false);
    input.setBorder(Ui.pad(2, 0, 2, 4));
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
    scroll =
        new JScrollPane(input) {
          @Override
          public Dimension getPreferredSize() {
            var width = Math.max(200, getWidth() > 0 ? getWidth() : 600);
            var line = input.getFontMetrics(input.getFont()).getHeight();
            var height = Math.min(line * 8 + 6, Math.max(line * 2 + 4, Ui.heightFor(input, width - 12)));
            return new Dimension(width, height);
          }
        };
    scroll.setBorder(null);
    scroll.setOpaque(false);
    scroll.getViewport().setOpaque(false);
    scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    add(scroll, BorderLayout.CENTER);

    var controls = new JPanel(new BorderLayout());
    controls.setOpaque(false);
    var left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    left.setOpaque(false);
    left.add(assignment);
    left.add(classifier);
    left.add(answer);
    controls.add(left, BorderLayout.WEST);
    send.addActionListener(event -> submit());
    controls.add(send, BorderLayout.EAST);
    add(controls, BorderLayout.SOUTH);
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
    assignment.setEnabled(!busy);
    classifier.setEnabled(!busy);
    answer.setEnabled(!busy);
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

  /** Circular Carolina/Navy send button, dimmed while empty or busy. */
  private final class Send extends JButton {
    Send() {
      setPreferredSize(new Dimension(32, 32));
      setBorderPainted(false);
      setContentAreaFilled(false);
      setFocusPainted(false);
      setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      setToolTipText("Send (Enter) · newline (Shift+Enter)");
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      var ready = !busy && !input.getText().isBlank();
      g.setColor(ready ? Theme.p.primary : (Theme.p.dark ? Theme.p.hover : Theme.p.border));
      g.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
      var arrow = Ui.icon("arrow-up", 18, ready ? Theme.p.primaryText : Theme.p.faint);
      arrow.paintIcon(this, g, (getWidth() - 18) / 2, (getHeight() - 18) / 2);
      g.dispose();
    }
  }
}

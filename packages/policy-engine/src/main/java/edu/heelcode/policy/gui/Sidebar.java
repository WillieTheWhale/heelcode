package edu.heelcode.policy.gui;

import edu.heelcode.policy.Models;
import edu.heelcode.policy.Threads;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/** Codex-style navigation: new thread, sections, the workspace's saved threads, workspace switcher. */
final class Sidebar extends JPanel {
  static final int WIDTH = 264;
  private final MainFrame frame;
  private final List<Item> nav = new ArrayList<>();
  private final Ui.Stack threads = new Ui.Stack(2);
  private final JLabel threadsTitle = Ui.section("Threads");
  private final JPanel footer = new JPanel(new BorderLayout());

  Sidebar(MainFrame frame) {
    super(new BorderLayout());
    this.frame = frame;
    setPreferredSize(new Dimension(WIDTH, 100));
    setBorder(Ui.pad(46, 10, 10, 10));

    var top = new Ui.Stack(2);
    var brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    brand.setOpaque(false);
    brand.add(Brand.mark(20));
    brand.add(Ui.label("HeelCode", 14f, Font.BOLD, Theme.p.text));
    if (frame.state.student) brand.add(Ui.Chip.neutral("Student"));
    top.add(Ui.gap(wrapRow(brand, 30), 12));
    var compose = new Item("compose", "New thread", null, () -> {
      frame.state.select("");
      frame.show("ask");
      frame.ask.focusComposer();
    });
    top.add(Ui.gap(compose, 6));
    addNav(top, "ask", "chat", "Ask");
    if (!frame.state.student) {
      addNav(top, "policies", "shield", "Policies");
      addNav(top, "tests", "flask", "Policy tests");
      addNav(top, "admin", "sliders", "Admin");
    }
    var header = new JPanel(new BorderLayout());
    header.setOpaque(false);
    header.add(top, BorderLayout.NORTH);
    var section = new JPanel(new BorderLayout());
    section.setOpaque(false);
    section.setBorder(Ui.pad(18, 8, 6, 4));
    section.add(threadsTitle, BorderLayout.WEST);
    header.add(section, BorderLayout.SOUTH);
    add(header, BorderLayout.NORTH);

    var scroll = Ui.scroll(threads);
    add(scroll, BorderLayout.CENTER);
    footer.setOpaque(false);
    add(footer, BorderLayout.SOUTH);
    frame.state.listen(this::refresh);
    refresh();
  }

  private void addNav(Ui.Stack top, String view, String icon, String title) {
    var item = new Item(icon, title, view, () -> frame.show(view));
    nav.add(item);
    top.add(item);
  }

  private static JComponent wrapRow(JComponent component, int height) {
    var panel = new JPanel(new BorderLayout());
    panel.setOpaque(false);
    panel.add(component, BorderLayout.WEST);
    panel.setPreferredSize(new Dimension(10, height));
    return panel;
  }

  void refresh() {
    for (var item : nav) item.selected = item.view != null && item.view.equals(frame.view);
    nav.forEach(JComponent::repaint);
    var state = frame.state;
    threads.removeAll();
    threadsTitle.setText(state.active == null ? "THREADS" : "THREADS · " + state.course());
    if (state.threads.isEmpty()) {
      var empty = Ui.wrap(state.active == null ? "Open a course workspace to see its threads." : "No threads yet. Ask a question to start one.", 12f, Theme.p.faint);
      empty.setBorder(Ui.pad(4, 10, 4, 10));
      threads.add(empty);
    }
    for (var summary : state.threads) threads.add(new ThreadItem(summary));
    threads.revalidate();
    threads.repaint();
    footer.removeAll();
    footer.add(workspaceButton(), BorderLayout.CENTER);
    var theme = Ui.ghost("", Theme.p.dark ? "sun" : "moon");
    theme.setToolTipText("Switch to " + (Theme.p.dark ? "light" : "dark") + " mode");
    theme.addActionListener(event -> frame.switchTheme());
    footer.add(theme, BorderLayout.EAST);
    footer.revalidate();
    footer.repaint();
  }

  private JComponent workspaceButton() {
    var state = frame.state;
    var title = state.workspace == null ? "No workspace" : state.active == null ? state.workspace.getFileName().toString() : state.course();
    var subtitle = state.workspace == null ? "Open a course folder" : state.active == null ? "Policy unavailable" : state.workspace.getFileName().toString();
    var button = new Item("folder", title, null, null);
    button.subtitle = subtitle;
    button.action = () -> {
      var menu = new JPopupMenu();
      var open = new JMenuItem("Open workspace…", Ui.icon("folder", 15, Theme.p.muted));
      open.addActionListener(event -> frame.openWorkspace());
      menu.add(open);
      if (State.demoAvailable()) {
        var demo = new JMenuItem("Open SYS 301 demo", Ui.icon("spark", 15, Theme.p.muted));
        demo.addActionListener(event -> frame.createDemo());
        menu.add(demo);
      }
      var recent = state.settings.recent.stream().filter(path -> state.workspace == null || !path.equals(state.workspace.toString())).toList();
      if (!recent.isEmpty()) menu.addSeparator();
      for (var path : recent) {
        var item = new JMenuItem(Path.of(path).getFileName().toString());
        item.setToolTipText(path);
        item.addActionListener(event -> {
          state.open(Path.of(path));
          frame.show("ask");
        });
        menu.add(item);
      }
      menu.show(button, 0, -menu.getPreferredSize().height - 4);
    };
    return button;
  }

  @Override
  protected void paintComponent(Graphics g) {
    g.setColor(Theme.p.sidebar);
    g.fillRect(0, 0, getWidth(), getHeight());
    g.setColor(Theme.p.border);
    g.fillRect(getWidth() - 1, 0, 1, getHeight());
  }

  /** A navigation row with hover and selection states. */
  static class Item extends JComponent {
    final String icon;
    final String title;
    final String view;
    String subtitle;
    Runnable action;
    boolean selected;
    boolean hover;

    Item(String icon, String title, String view, Runnable action) {
      this.icon = icon;
      this.title = title;
      this.view = view;
      this.action = action;
      setFont(Theme.ui(13f));
      Ui.hover(this, () -> { hover = true; repaint(); }, () -> { hover = false; repaint(); });
      Ui.click(this, () -> { if (this.action != null) this.action.run(); });
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(100, subtitle == null ? 32 : 44);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      if (selected || hover) {
        g.setColor(selected ? Theme.p.selected : Theme.p.hover);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
      }
      var color = selected ? Theme.p.text : Theme.p.muted;
      var glyph = Ui.icon(icon, 16, selected ? Theme.p.accent : Theme.p.muted);
      glyph.paintIcon(this, g, 10, (getHeight() - 16) / 2);
      g.setFont(getFont().deriveFont(selected ? Font.BOLD : Font.PLAIN));
      g.setColor(subtitle == null ? color : Theme.p.text);
      var metrics = g.getFontMetrics();
      var y = subtitle == null ? (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent() : 19;
      g.drawString(title, 36, y);
      if (subtitle != null) {
        g.setFont(Theme.ui(11.5f));
        g.setColor(Theme.p.faint);
        g.drawString(subtitle, 36, 34);
      }
      g.dispose();
    }
  }

  /** One saved policy session: its first request, assignment, turns, and age. */
  final class ThreadItem extends JComponent {
    final Threads.Summary summary;
    boolean hover;

    ThreadItem(Threads.Summary summary) {
      this.summary = summary;
      setToolTipText(summary.title());
      Ui.hover(this, () -> { hover = true; repaint(); }, () -> { hover = false; repaint(); });
      Ui.click(this, () -> {
        frame.state.select(summary.sessionId());
        frame.show("ask");
      });
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(100, 46);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      var selected = summary.sessionId().equals(frame.state.thread);
      if (selected || hover) {
        g.setColor(selected ? Theme.p.selected : Theme.p.hover);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
      }
      g.setFont(Theme.ui(12.5f, selected ? Font.BOLD : Font.PLAIN));
      g.setColor(selected ? Theme.p.text : Theme.mix(Theme.p.text, Theme.p.muted, 0.35));
      var metrics = g.getFontMetrics();
      var title = summary.title();
      var max = getWidth() - 20;
      while (metrics.stringWidth(title) > max && title.length() > 4) title = title.substring(0, title.length() - 2);
      if (!title.equals(summary.title())) title = title.strip() + "…";
      g.drawString(title, 10, 19);
      g.setFont(Theme.ui(11f));
      g.setColor(Theme.p.faint);
      var mode = summary.chat() ? Models.label(summary.model()) : "check only";
      g.drawString(summary.assignment() + " · " + summary.turns() + (summary.turns() == 1 ? " turn" : " turns") + " · " + mode, 10, 36);
      var age = Ui.ago(summary.updated());
      g.drawString(age, getWidth() - 10 - g.getFontMetrics().stringWidth(age), 36);
      g.dispose();
    }
  }
}

package edu.heelcode.policy.gui;

import edu.heelcode.policy.Threads;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The Codex sidebar: the product mark with a menu, search, a "New chat" row over a hairline, then
 * course folders. The open course nests its chats beneath it, as Codex nests threads under
 * projects. The mark is HeelCode's terminal wordmark, drawn in UNC Navy and Carolina pixels.
 */
final class Sidebar extends JPanel {
  static final int WIDTH = 280;
  private static final int VISIBLE_THREADS = 6;
  private final MainFrame frame;
  private final Ui.Stack list = new Ui.Stack(0);
  private final JTextField search = new JTextField();
  private final JComponent searchRow;
  private final PixelMark mark = new PixelMark(1.5, Theme.p.sidebar);
  private boolean expanded;
  private boolean collapsed;

  Sidebar(MainFrame frame) {
    super(new BorderLayout());
    this.frame = frame;
    setOpaque(false);
    setPreferredSize(new Dimension(WIDTH, 100));

    var top = new Ui.Stack(0);
    top.setBorder(Ui.pad(0, 7, 0, 7));
    top.add(new Header());
    searchRow = searchField();
    searchRow.setVisible(false);
    top.add(Ui.gap(searchRow, 4));
    top.add(new Row("compose", "New chat", false, () -> {
      frame.open("");
      frame.ask.focusComposer();
    }));
    var rule = new JComponent() {
      @Override
      protected void paintComponent(Graphics g) {
        g.setColor(Theme.p.subtle);
        g.fillRect(-7, getHeight() / 2, getWidth() + 14, 1);
      }
    };
    rule.setPreferredSize(new Dimension(10, 14));
    top.add(rule);
    add(top, BorderLayout.NORTH);

    list.setBorder(Ui.pad(0, 7, 16, 7));
    add(Ui.fading(list, Theme.p.sidebar), BorderLayout.CENTER);
    frame.state.listen(this::refresh);
    refresh();
  }

  void refresh() {
    var state = frame.state;
    var query = search.getText().strip().toLowerCase(Locale.ROOT);
    list.removeAll();
    if (state.workspace == null) {
      list.add(new Row("folder", "Open a course…", false, frame::openWorkspace));
      if (State.demoAvailable()) list.add(new Row("folder", "SYS 301 demo", false, frame::createDemo));
    } else {
      var name = state.active == null ? state.workspace.getFileName().toString() : state.course();
      list.add(new Row(collapsed ? "folder" : "folder-open", name, false, () -> {
        collapsed = !collapsed;
        refresh();
      }));
      if (!collapsed) {
        var threads = state.threads.stream().filter(summary -> query.isEmpty() || summary.title().toLowerCase(Locale.ROOT).contains(query)).toList();
        var shown = expanded || !query.isEmpty() ? threads : threads.stream().limit(VISIBLE_THREADS).toList();
        for (var summary : shown) list.add(new ThreadRow(summary));
        if (threads.isEmpty() && state.active != null) list.add(new Row(null, query.isEmpty() ? "No chats yet" : "No matching chats", true, null));
        if (threads.size() > VISIBLE_THREADS && query.isEmpty())
          list.add(new Row(null, expanded ? "Show less" : "Show more", true, () -> {
            expanded = !expanded;
            refresh();
          }));
      }
    }
    var others = state.settings.recent.stream().filter(path -> state.workspace == null || !path.equals(state.workspace.toString())).toList();
    var first = true;
    for (var path : others) {
      var folder = Path.of(path);
      var missing = !Files.isDirectory(folder);
      var row = new Row("folder", folder.getFileName().toString(), missing, missing ? null : () -> {
        state.open(folder);
        frame.show("ask");
      });
      if (first) Ui.gap((JComponent) list.getComponent(list.getComponentCount() - 1), 9);
      first = false;
      list.add(row);
    }
    if (state.workspace != null && State.demoAvailable() && others.stream().noneMatch(path -> path.endsWith("sys301-demo")) && !state.workspace.endsWith("sys301-demo")) {
      if (first) Ui.gap((JComponent) list.getComponent(list.getComponentCount() - 1), 9);
      list.add(new Row("folder", "SYS 301 demo", false, frame::createDemo));
    }
    list.revalidate();
    list.repaint();
  }

  private JComponent searchField() {
    search.putClientProperty("JTextField.placeholderText", "Search chats");
    search.putClientProperty("JTextField.leadingIcon", Ui.icon("search", 15, Theme.p.faint));
    search.setFont(Theme.ui(14f));
    Ui.style(search, "arc: 8; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; margin: 4,8,4,8; background: " + Theme.hex(Theme.over(Theme.p.hover, Theme.p.sidebar)) + "; placeholderForeground: " + Theme.hex(Theme.p.faint));
    search.setPreferredSize(new Dimension(10, 30));
    search.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent event) { refresh(); }
      public void removeUpdate(DocumentEvent event) { refresh(); }
      public void changedUpdate(DocumentEvent event) { refresh(); }
    });
    search.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
    search.getActionMap().put("close", new javax.swing.AbstractAction() {
      @Override
      public void actionPerformed(java.awt.event.ActionEvent event) {
        toggleSearch();
      }
    });
    return search;
  }

  private void toggleSearch() {
    var show = !searchRow.isVisible();
    searchRow.setVisible(show);
    if (show) search.requestFocusInWindow();
    else search.setText("");
    revalidate();
    repaint();
  }

  private void courseMenu(JComponent anchor) {
    var state = frame.state;
    var menu = new JPopupMenu();
    var open = new JMenuItem("Open course…", Ui.icon("folder", 15, Theme.p.muted));
    open.addActionListener(event -> frame.openWorkspace());
    menu.add(open);
    if (State.demoAvailable()) {
      var demo = new JMenuItem("Open SYS 301 demo", Ui.icon("spark", 15, Theme.p.muted));
      demo.addActionListener(event -> frame.createDemo());
      menu.add(demo);
    }
    if (state.workspace != null) {
      menu.addSeparator();
      var reveal = new JMenuItem("Show course folder", Ui.icon("folder-open", 15, Theme.p.muted));
      reveal.addActionListener(event -> {
        try {
          java.awt.Desktop.getDesktop().open(state.workspace.toFile());
        } catch (Exception error) {
          frame.toast(Ui.message(error), Theme.p.deny);
        }
      });
      menu.add(reveal);
      var where = new JMenuItem(state.workspace.toString());
      where.setEnabled(false);
      menu.add(where);
    }
    menu.addSeparator();
    var theme = new JMenuItem(Theme.p.dark ? "Light appearance" : "Dark appearance", Ui.icon(Theme.p.dark ? "sun" : "moon", 15, Theme.p.muted));
    theme.addActionListener(event -> frame.switchTheme());
    menu.add(theme);
    menu.show(anchor, 0, anchor.getHeight() + 4);
  }

  @Override
  protected void paintComponent(Graphics graphics) {
    var g = Ui.smooth(graphics);
    g.setColor(Theme.p.sidebar);
    g.fill(MainFrame.rounded(getWidth(), getHeight(), MainFrame.RADIUS, 0, 0, MainFrame.RADIUS));
    g.setColor(Theme.p.subtle);
    g.fillRect(getWidth() - 1, 0, 1, getHeight());
    g.dispose();
  }

  /** The pixel wordmark with a menu chevron, then search and appearance buttons, as Codex's header. */
  private final class Header extends JPanel {
    private final JButton find = MainFrame.toolbarIcon("search", "Search chats");
    private final JButton theme = MainFrame.toolbarIcon(Theme.p.dark ? "sun" : "moon", "Switch to " + (Theme.p.dark ? "light" : "dark") + " appearance");

    Header() {
      super(null);
      setOpaque(false);
      setPreferredSize(new Dimension(10, 50));
      var brand = new JComponent() {
        private boolean hover;

        {
          Ui.hover(this, () -> { hover = true; mark.sweep(); repaint(); }, () -> { hover = false; repaint(); });
          Ui.click(this, () -> courseMenu(this));
          setToolTipText("HeelCode · course menu");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
          var g = Ui.smooth(graphics);
          if (hover) {
            g.setColor(Theme.over(Theme.p.hover, Theme.p.sidebar));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
          }
          Ui.icon("chevron-down", 14, hover ? Theme.p.muted : Theme.p.faint).paintIcon(this, g, getWidth() - 21, (getHeight() - 14) / 2 + 1);
          g.dispose();
        }
      };
      brand.setLayout(null);
      brand.add(mark);
      var size = mark.getPreferredSize();
      mark.setBounds(7, (34 - size.height) / 2 + 1, size.width, size.height);
      brand.setBounds(0, 8, size.width + 7 + 26, 34);
      add(brand);
      find.addActionListener(event -> toggleSearch());
      theme.addActionListener(event -> frame.switchTheme());
      add(find);
      add(theme);
    }

    @Override
    public void doLayout() {
      find.setBounds(getWidth() - 31, 11, 28, 28);
      theme.setBounds(getWidth() - 65, 11, 28, 28);
    }
  }

  /** Paints a row label, fading it out at the right edge instead of truncating with an ellipsis. */
  static void fadeText(Graphics2D g, String text, int x, int right, int height, Color background) {
    var metrics = g.getFontMetrics();
    var y = (height - metrics.getHeight()) / 2 + metrics.getAscent();
    var clip = g.getClip();
    g.clipRect(x, 0, Math.max(0, right - x), height);
    g.drawString(text, x, y);
    g.setClip(clip);
    if (x + metrics.stringWidth(text) <= right) return;
    var fade = 26;
    g.setPaint(new GradientPaint(right - fade, 0, Theme.alpha(background, 0), right, 0, background));
    g.fillRect(right - fade, 0, fade, height);
  }

  private static Color rowFill(boolean selected, boolean hover) {
    if (selected) return Theme.over(Theme.p.hover, Theme.p.sidebar);
    if (hover) return Theme.over(Theme.alpha(Theme.p.text, Theme.p.dark ? 10 : 8), Theme.p.sidebar);
    return Theme.p.sidebar;
  }

  /** A Codex sidebar row: 16px icon, 14px label, 30px tall, hover fill, no other chrome. */
  private static final class Row extends JComponent {
    private final String icon;
    private final String text;
    private final boolean dim;
    private boolean hover;

    Row(String icon, String text, boolean dim, Runnable action) {
      this.icon = icon;
      this.text = text;
      this.dim = dim;
      if (action != null) {
        Ui.hover(this, () -> { hover = true; repaint(); }, () -> { hover = false; repaint(); });
        Ui.click(this, action);
      }
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(100, 30);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      var fill = rowFill(false, hover);
      if (hover) {
        g.setColor(fill);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
      }
      if (icon != null) Ui.icon(icon, 16, dim ? Theme.p.faint : Theme.p.muted).paintIcon(this, g, 8, (getHeight() - 16) / 2);
      g.setFont(Theme.ui(14f));
      g.setColor(dim ? Theme.p.faint : Theme.p.text);
      fadeText(g, text, 31, getWidth() - 8, getHeight(), fill);
      g.dispose();
    }
  }

  /** A chat nested under its course, aligned with the course name like Codex's project threads. */
  private final class ThreadRow extends JComponent {
    private final Threads.Summary summary;
    private boolean hover;

    ThreadRow(Threads.Summary summary) {
      this.summary = summary;
      setToolTipText("<html>" + escape(summary.title()) + "<br><span style='color:" + Theme.hex(Theme.p.faint) + "'>" + summary.assignment() + " · " + (summary.chat() ? "answers" : "policy check only") + " · " + Ui.ago(summary.updated()) + " ago</span></html>");
      Ui.hover(this, () -> { hover = true; repaint(); }, () -> { hover = false; repaint(); });
      Ui.click(this, () -> frame.open(summary.sessionId()));
    }

    @Override
    public Dimension getPreferredSize() {
      return new Dimension(100, 30);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
      var g = Ui.smooth(graphics);
      var selected = summary.sessionId().equals(frame.state.thread) && "ask".equals(frame.view);
      var fill = rowFill(selected, hover);
      if (selected || hover) {
        g.setColor(fill);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
      }
      g.setFont(Theme.ui(14f));
      g.setColor(selected ? Theme.p.emphasis : Theme.p.text);
      var right = getWidth() - 8;
      if (hover && !selected) {
        // Codex shows the age only while pointing at a row.
        g.setFont(Theme.ui(12.5f));
        g.setColor(Theme.p.faint);
        var age = Ui.ago(summary.updated());
        var metrics = g.getFontMetrics();
        right -= metrics.stringWidth(age) + 8;
        g.drawString(age, getWidth() - 8 - metrics.stringWidth(age), (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
        g.setFont(Theme.ui(14f));
        g.setColor(Theme.p.text);
      }
      fadeText(g, summary.title(), 31, right, getHeight(), fill);
      g.dispose();
    }
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}

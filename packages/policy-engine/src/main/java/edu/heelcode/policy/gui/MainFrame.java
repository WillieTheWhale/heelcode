package edu.heelcode.policy.gui;

import com.formdev.flatlaf.FlatClientProperties;
import edu.heelcode.policy.Fixtures;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/** The window: a Codex-style sidebar, one content view at a time, and transient toasts. */
final class MainFrame extends JFrame {
  final State state;
  final Sidebar sidebar;
  final AskView ask;
  final Map<String, JComponent> views = new LinkedHashMap<>();
  private final CardLayout cards = new CardLayout();
  private final JPanel content = new JPanel(cards);
  String view = "ask";
  private JComponent toast;
  private Timer toastTimer;

  MainFrame(State state) {
    super("HeelCode");
    this.state = state;
    setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
    getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
    getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
    getRootPane().putClientProperty(FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING, FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING_LARGE);
    setIconImage(Brand.icon(256));
    setMinimumSize(new java.awt.Dimension(1020, 660));
    setSize(1340, 860);
    setLocationRelativeTo(null);

    ask = new AskView(this);
    views.put("ask", ask);
    if (!state.student) {
      views.put("policies", new PolicyView(this));
      views.put("tests", new TestsView(this));
      views.put("admin", new AdminView(this));
    }
    content.setBackground(Theme.p.bg);
    views.forEach((name, component) -> content.add(component, name));
    sidebar = new Sidebar(this);

    var root =
        new JPanel(new BorderLayout()) {
          @Override
          protected void paintComponent(Graphics g) {
            g.setColor(Theme.p.bg);
            g.fillRect(0, 0, getWidth(), getHeight());
          }
        };
    root.add(sidebar, BorderLayout.WEST);
    root.add(content, BorderLayout.CENTER);
    setContentPane(root);
    setJMenuBar(menus());
    shortcuts();
  }

  void show(String name) {
    if (!views.containsKey(name)) return;
    view = name;
    cards.show(content, name);
    sidebar.refresh();
    if (views.get(name) instanceof Refreshable refreshable) refreshable.refresh();
  }

  interface Refreshable {
    void refresh();
  }

  /** Short non-blocking notice anchored to the bottom of the content area. */
  void toast(String text, Color tone) {
    var layers = getLayeredPane();
    if (toast != null) layers.remove(toast);
    if (toastTimer != null) toastTimer.stop();
    var card = new Ui.Card(Theme.p.raised, Theme.p.borderStrong);
    card.arc = 12;
    card.setLayout(new BorderLayout(10, 0));
    card.setBorder(Ui.pad(9, 14, 9, 16));
    var dot = new JComponent() {
      @Override
      protected void paintComponent(Graphics graphics) {
        var g = Ui.smooth(graphics);
        g.setColor(tone);
        g.fillOval(0, getHeight() / 2 - 4, 8, 8);
        g.dispose();
      }
    };
    dot.setPreferredSize(new java.awt.Dimension(8, 8));
    card.add(dot, BorderLayout.WEST);
    card.add(Ui.label(text, 12.5f, Font.PLAIN, Theme.p.text), BorderLayout.CENTER);
    var size = card.getPreferredSize();
    var area = content.getBounds();
    var origin = javax.swing.SwingUtilities.convertPoint(content.getParent(), area.x, area.y, layers);
    card.setBounds(origin.x + (area.width - size.width) / 2, origin.y + area.height - size.height - 24, size.width, size.height);
    layers.add(card, JLayeredPane.POPUP_LAYER);
    toast = card;
    layers.repaint();
    toastTimer = new Timer(3800, event -> {
      layers.remove(card);
      layers.repaint();
    });
    toastTimer.setRepeats(false);
    toastTimer.start();
  }

  void openWorkspace() {
    var chooser = new JFileChooser(state.workspace == null ? null : state.workspace.toFile());
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setDialogTitle("Open a course workspace");
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    state.open(chooser.getSelectedFile().toPath());
    if (state.active != null) toast("Opened " + state.course() + " workspace", Theme.p.allow);
    else toast(state.problem, Theme.p.clarify);
    show("ask");
  }

  void createDemo() {
    Ui.async(
        () -> Fixtures.createDemo(State.demoPath()),
        path -> {
          state.open(path);
          show("ask");
          toast("SYS 301 demo is ready: Luna-generated policy, fictional course", Theme.p.allow);
        },
        error -> toast(Ui.message(error), Theme.p.deny));
  }

  void switchTheme() {
    state.settings.theme = Theme.p.dark ? "light" : "dark";
    state.settings.save();
    var bounds = getBounds();
    var current = view;
    Theme.install(!Theme.p.dark);
    state.clear();
    var next = new MainFrame(state);
    next.setBounds(bounds);
    next.show(current);
    next.setVisible(true);
    dispose();
  }

  private JMenuBar menus() {
    var bar = new JMenuBar();
    var mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    var file = new JMenu("File");
    file.add(item("New Thread", KeyStroke.getKeyStroke(KeyEvent.VK_N, mask), () -> {
      state.select("");
      show("ask");
    }));
    file.add(item("Open Workspace…", KeyStroke.getKeyStroke(KeyEvent.VK_O, mask), this::openWorkspace));
    if (State.demoAvailable()) file.add(item("Open SYS 301 Demo", null, this::createDemo));
    bar.add(file);
    var viewMenu = new JMenu("View");
    var index = 1;
    for (var name : views.keySet()) {
      var title = Character.toUpperCase(name.charAt(0)) + name.substring(1);
      viewMenu.add(item(title, KeyStroke.getKeyStroke(KeyEvent.VK_0 + index++, mask), () -> show(name)));
    }
    viewMenu.addSeparator();
    viewMenu.add(item("Toggle Inspector", KeyStroke.getKeyStroke(KeyEvent.VK_I, mask), ask::toggleInspector));
    viewMenu.add(item("Toggle Light/Dark", KeyStroke.getKeyStroke(KeyEvent.VK_L, mask | InputEvent.SHIFT_DOWN_MASK), this::switchTheme));
    bar.add(viewMenu);
    return bar;
  }

  private JMenuItem item(String title, KeyStroke key, Runnable action) {
    var item = new JMenuItem(title);
    if (key != null) item.setAccelerator(key);
    item.addActionListener(event -> action.run());
    return item;
  }

  private void shortcuts() {
    var mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    var input = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
    input.put(KeyStroke.getKeyStroke(KeyEvent.VK_K, mask), "focus-composer");
    getRootPane().getActionMap().put("focus-composer", new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent event) {
        show("ask");
        ask.focusComposer();
      }
    });
  }
}

package edu.heelcode.policy.gui;

import com.formdev.flatlaf.FlatClientProperties;
import edu.heelcode.policy.Fixtures;
import edu.heelcode.policy.PromptStore;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;

/**
 * The Codex window: chrome-colored title bar and icon rail, with the sidebar and the main surface
 * sharing one rounded panel inset into the chrome (hairline border, 6px gutter right and below).
 * Views and chats are recorded so back/forward behave as they do in Codex.
 */
final class MainFrame extends JFrame {
  static final int TITLE = 42;
  static final int RAIL = 52;
  static final int GUTTER = 6;
  static final int RADIUS = 10;
  final State state;
  final Sidebar sidebar;
  final AskView ask;
  final Map<String, JComponent> views = new LinkedHashMap<>();
  private final CardLayout cards = new CardLayout();
  private final JPanel content = new JPanel(cards);
  private final Rail rail;
  private final JComponent separator = new JComponent() {
    @Override
    protected void paintComponent(Graphics g) {
      g.setColor(Theme.p.dark ? Theme.p.border : Theme.p.subtle);
      g.fillRect(0, 12, 1, getHeight() - 22);
    }
  };
  private final JPanel nav = new JPanel(null) {
    @Override
    public void doLayout() {
      // Back, forward and the sidebar toggle follow the traffic lights, as in Codex.
      var x = Math.max(62, lightsRight + 8);
      var y = lightsCenter - 14;
      back.setBounds(x, y, 28, 28);
      forward.setBounds(x + 34, y, 28, 28);
      toggle.setBounds(x + 67, y, 28, 28);
      separator.setBounds(getWidth() - 1, 0, 1, TITLE);
    }
  };
  private final JPanel titleCenter = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
  private final JPanel titleRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
  /** Vertical center and right edge of the macOS window buttons, once AppKit reports them. */
  private int lightsCenter = 20;
  private int lightsRight = 56;
  private final JLabel titleIcon = new JLabel();
  private final JLabel title = new JLabel();
  private final JLabel subtitle = new JLabel();
  private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
  private final JButton back = toolbarIcon("arrow-left", "Back (⌘[)");
  private final JButton forward = toolbarIcon("arrow-right", "Forward (⌘])");
  private final JButton toggle = toolbarIcon("panel-left", "Toggle sidebar (⌘B)");
  private final List<String[]> history = new ArrayList<>();
  private int position = -1;
  private boolean replaying;
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
    getRootPane().putClientProperty(FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING, FlatClientProperties.MACOS_WINDOW_BUTTONS_SPACING_MEDIUM);
    getRootPane().addPropertyChangeListener(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_BOUNDS, event -> alignToLights());
    setIconImage(Brand.icon(256));
    setMinimumSize(new Dimension(1040, 660));
    setSize(1360, 860);
    setLocationRelativeTo(null);

    ask = new AskView(this);
    views.put("ask", ask);
    if (!state.student) {
      views.put("policies", new PolicyView(this));
      views.put("tests", new TestsView(this));
      views.put("admin", new AdminView(this));
    }
    content.setOpaque(false);
    views.forEach((name, component) -> content.add(component, name));
    sidebar = new Sidebar(this);
    rail = new Rail();

    var root = new JPanel(new BorderLayout()) {
      @Override
      protected void paintComponent(Graphics g) {
        g.setColor(Theme.p.chrome);
        g.fillRect(0, 0, getWidth(), getHeight());
      }
    };
    root.add(titleBar(), BorderLayout.NORTH);
    root.add(rail, BorderLayout.WEST);
    var panel = new JPanel(new BorderLayout()) {
      @Override
      protected void paintComponent(Graphics graphics) {
        var g = Ui.smooth(graphics);
        g.setColor(Theme.p.bg);
        g.fill(panelShape(this));
        g.dispose();
      }

      @Override
      protected void paintChildren(Graphics graphics) {
        super.paintChildren(graphics);
        var g = Ui.smooth(graphics);
        g.setColor(Theme.p.dark ? Theme.p.border : Theme.p.subtle);
        var shape = panelShape(this);
        g.draw(new RoundRectangle2D.Double(shape.getX() + 0.5, shape.getY() + 0.5, shape.getWidth() - 1, shape.getHeight() - 1, RADIUS * 2, RADIUS * 2));
        g.dispose();
      }
    };
    panel.setOpaque(false);
    panel.setBorder(Ui.pad(0, 0, GUTTER, GUTTER));
    panel.add(sidebar, BorderLayout.WEST);
    panel.add(content, BorderLayout.CENTER);
    root.add(panel, BorderLayout.CENTER);
    setContentPane(root);
    setJMenuBar(menus());
    shortcuts();
    record();
  }

  private static RoundRectangle2D panelShape(JComponent panel) {
    return new RoundRectangle2D.Double(0, 0, panel.getWidth() - GUTTER, panel.getHeight() - GUTTER, RADIUS * 2, RADIUS * 2);
  }

  /** Back/forward and the sidebar toggle sit over the rail; a hairline marks the sidebar's edge. */
  private JComponent titleBar() {
    var bar = new JPanel(new BorderLayout());
    bar.setOpaque(false);
    bar.setPreferredSize(new Dimension(10, TITLE));
    nav.setOpaque(false);
    back.addActionListener(event -> navigate(-1));
    forward.addActionListener(event -> navigate(1));
    toggle.addActionListener(event -> toggleSidebar());
    nav.add(back);
    nav.add(forward);
    nav.add(toggle);
    nav.add(separator);
    sizeNav();
    bar.add(nav, BorderLayout.WEST);

    var center = titleCenter;
    center.setOpaque(false);
    center.setBorder(Ui.pad(lightsCenter - 9, 14, 0, 0));
    titleIcon.setIcon(Ui.icon("folder", 16, Theme.p.muted));
    titleIcon.setBorder(Ui.pad(0, 0, 0, 10));
    title.setFont(Theme.medium(14f));
    title.setForeground(Theme.p.text);
    subtitle.setFont(Theme.ui(13.5f));
    subtitle.setForeground(Theme.p.faint);
    subtitle.setBorder(Ui.pad(0, 9, 0, 0));
    center.add(titleIcon);
    center.add(title);
    center.add(subtitle);
    bar.add(center, BorderLayout.CENTER);

    var right = titleRight;
    right.setOpaque(false);
    right.setBorder(Ui.pad(lightsCenter - 14, 0, 0, 8));
    actions.setOpaque(false);
    right.add(actions);
    var rule = new JComponent() {
      @Override
      protected void paintComponent(Graphics g) {
        g.setColor(Theme.p.dark ? Theme.p.border : Theme.p.subtle);
        g.fillRect(6, 6, 1, 16);
      }
    };
    rule.setPreferredSize(new Dimension(13, 28));
    right.add(rule);
    var compose = toolbarIcon("compose", "New chat (⌘N)");
    compose.addActionListener(event -> {
      open("");
      ask.focusComposer();
    });
    right.add(compose);
    bar.add(right, BorderLayout.EAST);
    return bar;
  }

  private void alignToLights() {
    if (!(getRootPane().getClientProperty(FlatClientProperties.FULL_WINDOW_CONTENT_BUTTONS_BOUNDS) instanceof java.awt.Rectangle lights) || lights.isEmpty()) return;
    lightsCenter = Math.max(14, Math.min(TITLE - 14, lights.y + lights.height / 2));
    lightsRight = lights.x + lights.width;
    titleCenter.setBorder(Ui.pad(lightsCenter - 9, 14, 0, 0));
    titleRight.setBorder(Ui.pad(lightsCenter - 14, 0, 0, 8));
    sizeNav();
    getContentPane().revalidate();
    getContentPane().repaint();
  }

  static JButton toolbarIcon(String icon, String tip) {
    var button = Ui.ghost("", icon);
    button.setIcon(Ui.icon(icon, 16, Theme.p.muted));
    button.setRolloverIcon(Ui.icon(icon, 16, Theme.p.text));
    button.setDisabledIcon(Ui.icon(icon, 16, Theme.alpha(Theme.p.faint, 90)));
    button.setToolTipText(tip);
    button.setPreferredSize(new Dimension(28, 28));
    return button;
  }

  /** A toolbar icon that shows Codex's filled "on" state, e.g. the right-panel toggle. */
  static JButton toolbarToggle(String icon, String tip, boolean on) {
    var button = toolbarIcon(icon, tip);
    if (on) {
      button.setIcon(Ui.icon(icon, 16, Theme.p.emphasis));
      button.setRolloverIcon(Ui.icon(icon, 16, Theme.p.emphasis));
      button.putClientProperty(FlatClientProperties.BUTTON_TYPE, null);
      Ui.style(button, "arc: 8; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0; margin: 5,6,5,6; background: " + Theme.hex(Theme.p.selected) + "; hoverBackground: " + Theme.hex(Theme.p.selected) + "; pressedBackground: " + Theme.hex(Theme.p.borderStrong));
    }
    return button;
  }

  /** Sets the title-bar text and the view's action buttons (right side). */
  void title(String text, String detail, JComponent... buttons) {
    title.setText(text == null ? "" : text);
    subtitle.setText(detail == null ? "" : detail);
    titleIcon.setVisible(text != null && !text.isEmpty());
    actions.removeAll();
    for (var button : buttons) actions.add(button);
    actions.revalidate();
    actions.repaint();
  }

  void toggleSidebar() {
    sidebar.setVisible(!sidebar.isVisible());
    sizeNav();
    getContentPane().revalidate();
    getContentPane().repaint();
  }

  private void sizeNav() {
    var open = sidebar == null || sidebar.isVisible();
    nav.setPreferredSize(new Dimension(open ? RAIL + Sidebar.WIDTH : Math.max(62, lightsRight + 8) + 67 + 28 + 18, TITLE));
    nav.revalidate();
    nav.repaint();
  }

  void show(String name) {
    if (!views.containsKey(name)) return;
    view = name;
    cards.show(content, name);
    rail.repaint();
    sidebar.refresh();
    if (views.get(name) instanceof Refreshable refreshable) refreshable.refresh();
    record();
  }

  /** Opens a chat (or a new one when {@code thread} is empty) and records it for back/forward. */
  void open(String thread) {
    state.select(thread);
    show("ask");
  }

  private void record() {
    if (replaying) return;
    var entry = new String[] {view, state.thread};
    if (position >= 0 && history.get(position)[0].equals(entry[0]) && history.get(position)[1].equals(entry[1])) return;
    while (history.size() > position + 1) history.remove(history.size() - 1);
    history.add(entry);
    position = history.size() - 1;
    back.setEnabled(position > 0);
    forward.setEnabled(false);
  }

  /** A new chat that just received its ID keeps its place in history instead of adding one. */
  void threadChanged() {
    if (position >= 0 && history.get(position)[0].equals("ask") && history.get(position)[1].isEmpty() && !state.thread.isEmpty()) {
      history.set(position, new String[] {"ask", state.thread});
      return;
    }
    record();
  }

  private void navigate(int step) {
    var target = position + step;
    if (target < 0 || target >= history.size()) return;
    position = target;
    replaying = true;
    try {
      var entry = history.get(position);
      if (entry[0].equals("ask")) state.select(entry[1]);
      show(entry[0]);
    } finally {
      replaying = false;
    }
    back.setEnabled(position > 0);
    forward.setEnabled(position < history.size() - 1);
  }

  interface Refreshable {
    void refresh();
  }

  /** Short notice anchored above the bottom of the main surface. */
  void toast(String text, Color tone) {
    var layers = getLayeredPane();
    if (toast != null) layers.remove(toast);
    if (toastTimer != null) toastTimer.stop();
    var card = new Ui.Card(Theme.p.raised, Theme.p.border);
    card.arc = 999;
    card.setLayout(new BorderLayout(9, 0));
    card.setBorder(Ui.pad(8, 14, 8, 16));
    var dot = new JComponent() {
      @Override
      protected void paintComponent(Graphics graphics) {
        var g = Ui.smooth(graphics);
        g.setColor(tone);
        g.fillOval(0, getHeight() / 2 - 3, 7, 7);
        g.dispose();
      }
    };
    dot.setPreferredSize(new Dimension(7, 7));
    card.add(dot, BorderLayout.WEST);
    card.add(Ui.label(text, 13f, Font.PLAIN, Theme.p.text), BorderLayout.CENTER);
    var size = card.getPreferredSize();
    var area = content.getBounds();
    var origin = SwingUtilities.convertPoint(content.getParent(), area.x, area.y, layers);
    card.setBounds(origin.x + (area.width - size.width) / 2, origin.y + area.height - size.height - 28, size.width, size.height);
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
    if (state.active != null) toast("Opened " + state.course(), Theme.p.allow);
    else toast(state.problem, Theme.p.clarify);
    show("ask");
  }

  void createDemo() {
    Ui.async(
        () -> Fixtures.createDemo(State.demoPath()),
        path -> {
          state.open(path);
          show("ask");
          toast("SYS 301 demo is ready", Theme.p.allow);
        },
        error -> toast(Ui.message(error), Theme.p.deny));
  }

  void switchTheme() {
    state.settings.theme = Theme.p.dark ? "light" : "dark";
    state.settings.save();
    var bounds = getBounds();
    var current = view;
    var sidebarVisible = sidebar.isVisible();
    Theme.install(!Theme.p.dark);
    state.clear();
    var next = new MainFrame(state);
    next.setBounds(bounds);
    if (!sidebarVisible) next.toggleSidebar();
    next.show(current);
    next.setVisible(true);
    dispose();
  }

  private JMenuBar menus() {
    var bar = new JMenuBar();
    var mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    var file = new JMenu("File");
    file.add(item("New Chat", KeyStroke.getKeyStroke(KeyEvent.VK_N, mask), () -> {
      open("");
      ask.focusComposer();
    }));
    file.add(item("Open Course…", KeyStroke.getKeyStroke(KeyEvent.VK_O, mask), this::openWorkspace));
    if (State.demoAvailable()) file.add(item("Open SYS 301 Demo", null, this::createDemo));
    bar.add(file);
    var viewMenu = new JMenu("View");
    var index = 1;
    for (var name : views.keySet())
      viewMenu.add(item(Rail.title(name), KeyStroke.getKeyStroke(KeyEvent.VK_0 + index++, mask), () -> show(name)));
    viewMenu.addSeparator();
    viewMenu.add(item("Back", KeyStroke.getKeyStroke(KeyEvent.VK_OPEN_BRACKET, mask), () -> navigate(-1)));
    viewMenu.add(item("Forward", KeyStroke.getKeyStroke(KeyEvent.VK_CLOSE_BRACKET, mask), () -> navigate(1)));
    viewMenu.addSeparator();
    viewMenu.add(item("Toggle Sidebar", KeyStroke.getKeyStroke(KeyEvent.VK_B, mask), this::toggleSidebar));
    viewMenu.add(item("Toggle Policy Card", KeyStroke.getKeyStroke(KeyEvent.VK_I, mask), ask::toggleCard));
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

  /** Codex's icon rail: 36px buttons with 18px icons, a status dot, and the avatar at the bottom. */
  private final class Rail extends JPanel {
    private final JComponent avatar;
    private final String[][] items = {{"ask", "chat", "Ask"}, {"policies", "shield", "Policies"}, {"tests", "flask", "Policy tests"}, {"admin", "sliders", "Admin prompts"}};

    Rail() {
      super(null);
      setOpaque(false);
      setPreferredSize(new Dimension(RAIL, 100));
      var y = 8;
      for (var item : items) {
        if (!views.containsKey(item[0])) continue;
        var button = new RailButton(item[0], item[1], item[2]);
        button.setBounds(8, y, 36, 36);
        add(button);
        y += 43;
      }
      avatar = new JComponent() {
        @Override
        protected void paintComponent(Graphics graphics) {
          // UNC Navy with a Carolina monogram.
          var g = Ui.smooth(graphics);
          g.setColor(Theme.NAVY);
          g.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
          g.setColor(Theme.alpha(Theme.CAROLINA_LIGHT, 70));
          g.drawOval(0, 0, getWidth() - 1, getHeight() - 1);
          g.setFont(Theme.semibold(10.5f));
          g.setColor(Theme.CAROLINA_LIGHT);
          var initials = initials();
          var m = g.getFontMetrics();
          g.drawString(initials, (getWidth() - m.stringWidth(initials)) / 2f, (getHeight() - m.getHeight()) / 2f + m.getAscent());
          g.dispose();
        }
      };
      avatar.setToolTipText(System.getProperty("user.name"));
      add(avatar);
    }

    @Override
    public void doLayout() {
      avatar.setBounds(13, getHeight() - GUTTER - 36, 26, 26);
    }

    static String title(String view) {
      return switch (view) {
        case "ask" -> "Ask";
        case "policies" -> "Policies";
        case "tests" -> "Policy Tests";
        default -> "Admin";
      };
    }

    private String initials() {
      var name = System.getProperty("user.name", "you");
      return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
    }

    private final class RailButton extends JComponent {
      private final String target;
      private final String icon;
      private boolean hover;

      RailButton(String target, String icon, String tip) {
        this.target = target;
        this.icon = icon;
        setToolTipText(tip);
        Ui.hover(this, () -> { hover = true; repaint(); }, () -> { hover = false; repaint(); });
        Ui.click(this, () -> MainFrame.this.show(target));
      }

      @Override
      protected void paintComponent(Graphics graphics) {
        var g = Ui.smooth(graphics);
        var selected = target.equals(view);
        if (selected || hover) {
          g.setColor(selected ? Theme.p.selected : Theme.p.hover);
          g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
        }
        var glyph = Ui.icon(icon, 18, selected ? Theme.p.emphasis : hover ? Theme.p.text : Theme.p.muted);
        glyph.paintIcon(this, g, (getWidth() - 18) / 2, (getHeight() - 18) / 2);
        if (badge()) {
          // Codex's blue status dot, in Carolina: custom admin prompts are in effect.
          g.setColor(selected ? Theme.over(Theme.p.selected, Theme.p.chrome) : Theme.p.chrome);
          g.fillOval(getWidth() - 13, 2, 11, 11);
          g.setColor(Theme.CAROLINA);
          g.fillOval(getWidth() - 11, 4, 7, 7);
        }
        g.dispose();
      }

      private boolean badge() {
        if (!target.equals("admin")) return false;
        var store = PromptStore.open();
        return store.custom(PromptStore.Kind.CLASSIFIER) || store.custom(PromptStore.Kind.SYSTEM);
      }
    }
  }

  static JLabel centered(String text, Font font, Color color) {
    var label = new JLabel(text, SwingConstants.CENTER);
    label.setFont(font);
    label.setForeground(color);
    return label;
  }

  /** A rectangle with independent corner radii (top-left, top-right, bottom-right, bottom-left). */
  static Path2D rounded(double width, double height, double tl, double tr, double br, double bl) {
    var path = new Path2D.Double();
    path.moveTo(tl, 0);
    path.lineTo(width - tr, 0);
    path.quadTo(width, 0, width, tr);
    path.lineTo(width, height - br);
    path.quadTo(width, height, width - br, height);
    path.lineTo(bl, height);
    path.quadTo(0, height, 0, height - bl);
    path.lineTo(0, tl);
    path.quadTo(0, 0, tl, 0);
    path.closePath();
    return path;
  }
}

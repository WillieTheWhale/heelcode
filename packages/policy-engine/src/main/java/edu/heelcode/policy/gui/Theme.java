package edu.heelcode.policy.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;

/**
 * The Codex desktop design system (tokens read from the installed app's CSS and sampled from its
 * window): neutral surfaces, 8%/4%/16% foreground borders, 70%/50% text tiers, a 4px spacing
 * unit, and 14px base type. Only the accent differs: Carolina Blue, with UNC Navy in light mode.
 */
final class Theme {
  static final class Palette {
    final boolean dark;
    /** Main thread surface. */
    final Color bg;
    /** Title bar and icon rail. */
    final Color chrome;
    final Color sidebar;
    /** Cards and inputs placed on the main surface. */
    final Color surface;
    /** Menus and popovers. */
    final Color raised;
    final Color composer;
    final Color hover;
    final Color selected;
    final Color border;
    final Color subtle;
    final Color borderStrong;
    final Color text;
    final Color emphasis;
    final Color muted;
    final Color faint;
    final Color accent;
    final Color primary;
    final Color primaryText;
    final Color bubble;
    final Color allow;
    final Color deny;
    final Color clarify;
    final Color code;

    Palette(boolean dark, Color bg, Color chrome, Color sidebar, Color surface, Color raised, Color composer,
        Color hover, Color selected, Color border, Color subtle, Color borderStrong, Color text, Color emphasis,
        Color muted, Color faint, Color accent, Color primary, Color primaryText, Color bubble, Color allow,
        Color deny, Color clarify, Color code) {
      this.dark = dark;
      this.bg = bg;
      this.chrome = chrome;
      this.sidebar = sidebar;
      this.surface = surface;
      this.raised = raised;
      this.composer = composer;
      this.hover = hover;
      this.selected = selected;
      this.border = border;
      this.subtle = subtle;
      this.borderStrong = borderStrong;
      this.text = text;
      this.emphasis = emphasis;
      this.muted = muted;
      this.faint = faint;
      this.accent = accent;
      this.primary = primary;
      this.primaryText = primaryText;
      this.bubble = bubble;
      this.allow = allow;
      this.deny = deny;
      this.clarify = clarify;
      this.code = code;
    }
  }

  static final Color CAROLINA = new Color(0x4B9CD3);
  static final Color CAROLINA_LIGHT = new Color(0x7BAFD4);
  static final Color NAVY = new Color(0x13294B);

  static Palette p = dark();
  private static boolean fonts;

  static Palette dark() {
    var white = Color.WHITE;
    return new Palette(
        true,
        new Color(0x111111),
        new Color(0x1C1C1C),
        new Color(0x151515),
        new Color(0x1A1A1A),
        new Color(0x262626),
        new Color(0x2F2F2F),
        alpha(white, 20),
        alpha(white, 28),
        alpha(white, 20),
        alpha(white, 11),
        alpha(white, 41),
        new Color(0xDFDFDF),
        Color.WHITE,
        new Color(0xB3B3B3),
        new Color(0x8F8F8F),
        CAROLINA_LIGHT,
        CAROLINA_LIGHT,
        NAVY,
        new Color(0x1D1D1D),
        new Color(0x5EC58C),
        new Color(0xF07F7F),
        new Color(0xF0B35A),
        new Color(0x191919));
  }

  static Palette light() {
    var ink = new Color(0x1A1C1F);
    return new Palette(
        false,
        Color.WHITE,
        new Color(0xF3F3F3),
        new Color(0xF9F9F9),
        Color.WHITE,
        Color.WHITE,
        Color.WHITE,
        alpha(ink, 13),
        alpha(ink, 20),
        alpha(ink, 20),
        alpha(ink, 13),
        alpha(ink, 31),
        ink,
        new Color(0x0D0D0D),
        new Color(0x5F6064),
        new Color(0x8D8E90),
        CAROLINA,
        NAVY,
        Color.WHITE,
        new Color(0xF3F3F3),
        new Color(0x1E874A),
        new Color(0xC93C45),
        new Color(0xA56A00),
        new Color(0xF9F9F9));
  }

  static void install(boolean dark) {
    p = dark ? dark() : light();
    if (!fonts) {
      FlatJetBrainsMonoFont.install();
      fonts = true;
    }
    if (dark) FlatDarkLaf.setup();
    else FlatLightLaf.setup();
    var d = UIManager.getDefaults();
    d.put("defaultFont", ((Font) d.get("defaultFont")).deriveFont(14f));
    d.put("Component.arc", 10);
    d.put("Button.arc", 999);
    d.put("TextComponent.arc", 10);
    d.put("CheckBox.arc", 4);
    d.put("Component.focusWidth", 0);
    d.put("Component.innerFocusWidth", 0);
    d.put("Component.focusColor", ui(p.accent));
    d.put("Component.borderColor", ui(p.border));
    d.put("Component.disabledBorderColor", ui(p.subtle));
    d.put("Component.focusedBorderColor", ui(alpha(p.accent, 180)));
    d.put("Component.accentColor", ui(p.accent));
    d.put("Panel.background", ui(p.bg));
    d.put("Viewport.background", ui(p.bg));
    d.put("ScrollPane.background", ui(p.bg));
    d.put("ScrollBar.width", 10);
    d.put("ScrollBar.thumbArc", 999);
    d.put("ScrollBar.thumbInsets", new Insets(2, 3, 2, 2));
    d.put("ScrollBar.track", ui(alpha(p.bg, 0)));
    d.put("ScrollBar.background", ui(p.bg));
    d.put("ScrollBar.hoverTrackColor", ui(alpha(p.text, 0)));
    d.put("ScrollBar.thumb", ui(Theme.over(alpha(p.text, p.dark ? 40 : 46), p.bg)));
    d.put("ScrollBar.hoverThumbColor", ui(alpha(p.text, 90)));
    d.put("ScrollBar.showButtons", false);
    d.put("TextField.background", ui(p.surface));
    d.put("TextArea.background", ui(p.surface));
    d.put("ComboBox.background", ui(p.surface));
    d.put("TextComponent.selectionBackground", ui(alpha(p.accent, p.dark ? 90 : 70)));
    d.put("TextComponent.selectionForeground", ui(p.emphasis));
    d.put("PopupMenu.borderCornerRadius", 12);
    d.put("PopupMenu.background", ui(p.raised));
    d.put("PopupMenu.borderColor", ui(p.border));
    d.put("Popup.borderCornerRadius", 12);
    d.put("MenuItem.selectionBackground", ui(p.hover));
    d.put("MenuItem.selectionForeground", ui(p.emphasis));
    d.put("MenuItem.selectionArc", 8);
    d.put("MenuItem.selectionInsets", new Insets(0, 4, 0, 4));
    d.put("MenuItem.minimumWidth", 220);
    d.put("Table.background", ui(p.bg));
    d.put("Table.selectionBackground", ui(p.hover));
    d.put("Table.selectionForeground", ui(p.emphasis));
    d.put("Table.gridColor", ui(p.subtle));
    d.put("TableHeader.background", ui(p.bg));
    d.put("TableHeader.separatorColor", ui(alpha(p.bg, 0)));
    d.put("TableHeader.bottomSeparatorColor", ui(p.border));
    d.put("ToolTip.background", ui(p.raised));
    d.put("ToolTip.foreground", ui(p.text));
    d.put("SplitPane.background", ui(p.bg));
    d.put("SplitPaneDivider.draggingColor", ui(p.accent));
    d.put("Separator.foreground", ui(p.border));
    d.put("CheckBox.icon.checkmarkColor", ui(p.primaryText));
    d.put("CheckBox.icon.selectedBackground", ui(p.primary));
    d.put("CheckBox.icon.selectedBorderColor", ui(p.primary));
    d.put("Label.foreground", ui(p.text));
    d.put("TitlePane.unifiedBackground", true);
    FlatLaf.updateUI();
  }

  static Font ui(float size) {
    return ui(size, Font.PLAIN);
  }

  static Font ui(float size, int style) {
    return UIManager.getFont("defaultFont").deriveFont(style, size);
  }

  /** SF's medium weight, which Codex uses for titles and controls. */
  static Font medium(float size) {
    var attributes = new java.util.HashMap<java.awt.font.TextAttribute, Object>();
    attributes.put(java.awt.font.TextAttribute.WEIGHT, java.awt.font.TextAttribute.WEIGHT_MEDIUM);
    attributes.put(java.awt.font.TextAttribute.SIZE, size);
    return UIManager.getFont("defaultFont").deriveFont(attributes);
  }

  static Font semibold(float size) {
    var attributes = new java.util.HashMap<java.awt.font.TextAttribute, Object>();
    attributes.put(java.awt.font.TextAttribute.WEIGHT, java.awt.font.TextAttribute.WEIGHT_SEMIBOLD);
    attributes.put(java.awt.font.TextAttribute.SIZE, size);
    return UIManager.getFont("defaultFont").deriveFont(attributes);
  }

  static Font mono(float size) {
    return new Font(FlatJetBrainsMonoFont.FAMILY, Font.PLAIN, 12).deriveFont(size);
  }

  static Color alpha(Color color, int alpha) {
    return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
  }

  static Color mix(Color a, Color b, double t) {
    return new Color(
        (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
        (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
        (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
  }

  /** A translucent token flattened onto a background, for APIs that need an opaque color. */
  static Color over(Color top, Color under) {
    return mix(under, new Color(top.getRed(), top.getGreen(), top.getBlue()), top.getAlpha() / 255.0);
  }

  /** #rrggbb, or #rrggbbaa for translucent tokens (FlatLaf styles accept both). */
  static String hex(Color color) {
    return color.getAlpha() == 255
        ? String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue())
        : String.format("#%02x%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha());
  }

  /** Carolina for text and small marks: the light tint on dark, a deeper mix on white. */
  static Color unc() {
    return p.dark ? CAROLINA_LIGHT : mix(CAROLINA, NAVY, 0.32);
  }

  static Color effect(String effect) {
    return switch (effect) {
      case "allow" -> p.allow;
      case "deny" -> p.deny;
      default -> p.clarify;
    };
  }

  private static ColorUIResource ui(Color color) {
    return new ColorUIResource(color);
  }
}

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
 * Codex-like neutral surfaces with Carolina Blue (#4B9CD3 / #7BAFD4) and UNC Navy (#13294B)
 * accents. Dark mode is tinted slightly toward navy; light mode uses navy for primary actions.
 */
final class Theme {
  static final class Palette {
    final boolean dark;
    final Color bg;
    final Color sidebar;
    final Color surface;
    final Color raised;
    final Color hover;
    final Color selected;
    final Color border;
    final Color borderStrong;
    final Color text;
    final Color muted;
    final Color faint;
    final Color accent;
    final Color accentSoft;
    final Color primary;
    final Color primaryText;
    final Color bubble;
    final Color allow;
    final Color deny;
    final Color clarify;
    final Color code;

    Palette(boolean dark, Color bg, Color sidebar, Color surface, Color raised, Color hover, Color selected, Color border, Color borderStrong, Color text, Color muted, Color faint, Color accent, Color accentSoft, Color primary, Color primaryText, Color bubble, Color allow, Color deny, Color clarify, Color code) {
      this.dark = dark;
      this.bg = bg;
      this.sidebar = sidebar;
      this.surface = surface;
      this.raised = raised;
      this.hover = hover;
      this.selected = selected;
      this.border = border;
      this.borderStrong = borderStrong;
      this.text = text;
      this.muted = muted;
      this.faint = faint;
      this.accent = accent;
      this.accentSoft = accentSoft;
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
    return new Palette(
        true,
        new Color(0x15181D),
        new Color(0x0F1216),
        new Color(0x1B1F25),
        new Color(0x20252C),
        new Color(0x232931),
        new Color(0x1E2A38),
        new Color(0x272D35),
        new Color(0x363E49),
        new Color(0xE7EBF0),
        new Color(0x99A3B1),
        new Color(0x66707E),
        CAROLINA_LIGHT,
        new Color(123, 175, 212, 34),
        CAROLINA_LIGHT,
        new Color(0x0B1A2E),
        new Color(0x23282F),
        new Color(0x5BC68D),
        new Color(0xF0767D),
        new Color(0xE8B45A),
        new Color(0x101317));
  }

  static Palette light() {
    return new Palette(
        false,
        new Color(0xFFFFFF),
        new Color(0xF5F7FA),
        new Color(0xFFFFFF),
        new Color(0xF7F9FB),
        new Color(0xEEF2F6),
        new Color(0xE3EEF8),
        new Color(0xE3E8EE),
        new Color(0xCBD3DD),
        new Color(0x1A1F26),
        new Color(0x5B6675),
        new Color(0x8A94A3),
        CAROLINA,
        new Color(75, 156, 211, 30),
        NAVY,
        Color.WHITE,
        new Color(0xF0F3F7),
        new Color(0x1E874A),
        new Color(0xC93C45),
        new Color(0xA56A00),
        new Color(0xF5F7FA));
  }

  static void install(boolean dark) {
    p = dark ? dark() : light();
    if (!fonts) {
      FlatJetBrainsMonoFont.install();
      fonts = true;
    }
    if (dark) FlatDarkLaf.setup();
    else FlatLightLaf.setup();
    var defaults = UIManager.getDefaults();
    defaults.put("Component.arc", 10);
    defaults.put("Button.arc", 10);
    defaults.put("TextComponent.arc", 10);
    defaults.put("CheckBox.arc", 6);
    defaults.put("Component.focusWidth", 0);
    defaults.put("Component.innerFocusWidth", 0);
    defaults.put("Component.focusColor", ui(p.accent));
    defaults.put("Component.borderColor", ui(p.border));
    defaults.put("Component.focusedBorderColor", ui(p.accent));
    defaults.put("Component.accentColor", ui(p.accent));
    defaults.put("Panel.background", ui(p.bg));
    defaults.put("Viewport.background", ui(p.bg));
    defaults.put("ScrollPane.background", ui(p.bg));
    defaults.put("ScrollBar.width", 10);
    defaults.put("ScrollBar.thumbArc", 999);
    defaults.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
    defaults.put("ScrollBar.track", ui(p.bg));
    defaults.put("ScrollBar.thumb", ui(p.borderStrong));
    defaults.put("ScrollBar.hoverThumbColor", ui(p.faint));
    defaults.put("ScrollBar.showButtons", false);
    defaults.put("TextField.background", ui(p.surface));
    defaults.put("TextArea.background", ui(p.surface));
    defaults.put("TextComponent.selectionBackground", ui(alpha(p.accent, p.dark ? 80 : 70)));
    defaults.put("TextComponent.selectionForeground", ui(p.text));
    defaults.put("PopupMenu.borderCornerRadius", 12);
    defaults.put("PopupMenu.background", ui(p.raised));
    defaults.put("PopupMenu.borderColor", ui(p.borderStrong));
    defaults.put("MenuItem.selectionBackground", ui(p.hover));
    defaults.put("MenuItem.selectionForeground", ui(p.text));
    defaults.put("MenuItem.selectionArc", 8);
    defaults.put("MenuItem.selectionInsets", new Insets(0, 4, 0, 4));
    defaults.put("Table.background", ui(p.bg));
    defaults.put("Table.selectionBackground", ui(p.selected));
    defaults.put("Table.selectionForeground", ui(p.text));
    defaults.put("Table.gridColor", ui(p.border));
    defaults.put("TableHeader.background", ui(p.bg));
    defaults.put("TableHeader.separatorColor", ui(p.bg));
    defaults.put("TableHeader.bottomSeparatorColor", ui(p.border));
    defaults.put("ToolTip.background", ui(p.raised));
    defaults.put("ToolTip.foreground", ui(p.text));
    defaults.put("SplitPane.background", ui(p.bg));
    defaults.put("SplitPaneDivider.draggingColor", ui(p.accent));
    defaults.put("Separator.foreground", ui(p.border));
    defaults.put("CheckBox.icon.checkmarkColor", ui(p.primaryText));
    defaults.put("CheckBox.icon.selectedBackground", ui(p.primary));
    defaults.put("CheckBox.icon.selectedBorderColor", ui(p.primary));
    defaults.put("Label.foreground", ui(p.text));
    defaults.put("TitlePane.unifiedBackground", true);
    FlatLaf.updateUI();
  }

  static Font ui(float size) {
    return ui(size, Font.PLAIN);
  }

  static Font ui(float size, int style) {
    return UIManager.getFont("defaultFont").deriveFont(style, size);
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

  static String hex(Color color) {
    return String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
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

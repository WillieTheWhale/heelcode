package edu.heelcode.policy.gui;

import java.awt.Font;
import java.util.List;
import javax.swing.JEditorPane;
import javax.swing.text.html.HTMLEditorKit;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Renders model answers. Raw HTML is escaped and images are dropped, so nothing is fetched. */
final class Markdown {
  private static final Parser PARSER =
      Parser.builder().extensions(List.of(TablesExtension.create())).build();
  private static final HtmlRenderer RENDERER =
      HtmlRenderer.builder()
          .extensions(List.of(TablesExtension.create()))
          .escapeHtml(true)
          .sanitizeUrls(true)
          .build();

  static String html(String markdown) {
    var body = RENDERER.render(PARSER.parse(markdown));
    return body.replaceAll("<img[^>]*alt=\"([^\"]*)\"[^>]*>", "[$1]").replaceAll("<img[^>]*>", "");
  }

  static JEditorPane pane(String markdown) {
    var pane = new JEditorPane();
    var kit = new HTMLEditorKit();
    pane.setEditorKit(kit);
    var p = Theme.p;
    var font = Theme.ui(13.5f, Font.PLAIN);
    var sheet = kit.getStyleSheet();
    sheet.addRule(
        "body { font-family: '"
            + font.getFamily()
            + "'; font-size: 13.5pt; color: "
            + Theme.hex(p.text)
            + "; margin: 0; }");
    sheet.addRule("p { margin-top: 0; margin-bottom: 9px; }");
    sheet.addRule("h1, h2, h3, h4 { margin-top: 10px; margin-bottom: 6px; color: " + Theme.hex(p.text) + "; }");
    sheet.addRule("h1 { font-size: 17pt; } h2 { font-size: 15.5pt; } h3 { font-size: 14pt; } h4 { font-size: 13.5pt; }");
    sheet.addRule("ul, ol { margin-top: 0; margin-bottom: 9px; margin-left: 20px; }");
    sheet.addRule("li { margin-bottom: 3px; }");
    sheet.addRule(
        "code { font-family: 'JetBrains Mono'; font-size: 12pt; background-color: "
            + Theme.hex(p.dark ? p.raised : p.hover)
            + "; color: "
            + Theme.hex(p.dark ? p.accent : Theme.NAVY)
            + "; }");
    sheet.addRule(
        "pre { font-family: 'JetBrains Mono'; font-size: 12pt; background-color: "
            + Theme.hex(p.code)
            + "; border: 1px solid "
            + Theme.hex(p.border)
            + "; padding: 10px; margin-top: 2px; margin-bottom: 10px; }");
    sheet.addRule("pre code { background-color: " + Theme.hex(p.code) + "; color: " + Theme.hex(p.text) + "; }");
    sheet.addRule("a { color: " + Theme.hex(p.accent) + "; }");
    sheet.addRule("blockquote { margin-left: 4px; padding-left: 10px; border-left: 3px solid " + Theme.hex(p.borderStrong) + "; color: " + Theme.hex(p.muted) + "; }");
    sheet.addRule("table { border-collapse: collapse; margin-bottom: 10px; }");
    sheet.addRule("th, td { border: 1px solid " + Theme.hex(p.border) + "; padding: 4px 8px; }");
    pane.setText("<html><body>" + html(markdown) + "</body></html>");
    pane.setEditable(false);
    pane.setOpaque(false);
    pane.setBorder(null);
    pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    pane.setCaretPosition(0);
    return pane;
  }
}

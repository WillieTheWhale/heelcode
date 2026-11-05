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
    var kit =
        new HTMLEditorKit() {
          @Override
          public javax.swing.text.ViewFactory getViewFactory() {
            return new HTMLFactory() {
              @Override
              public javax.swing.text.View create(javax.swing.text.Element element) {
                var view = super.create(element);
                // Codex sets prose at ~1.6 leading. Swing's CSS ignores line-height, so plain
                // paragraphs (not <pre> line views) get explicit line spacing instead.
                if (view.getClass() != javax.swing.text.html.ParagraphView.class) return view;
                return new javax.swing.text.html.ParagraphView(element) {
                  @Override
                  protected void setPropertiesFromAttributes() {
                    super.setPropertiesFromAttributes();
                    setLineSpacing(0.26f);
                  }
                };
              }
            };
          }
        };
    pane.setEditorKit(kit);
    var p = Theme.p;
    var font = Theme.ui(14f, Font.PLAIN);
    var sheet = kit.getStyleSheet();
    sheet.addRule(
        "body { font-family: '"
            + font.getFamily()
            + "'; font-size: 14pt; color: "
            + Theme.hex(p.text)
            + "; margin: 0; }");
    sheet.addRule("p { margin-top: 0; margin-bottom: 9px; }");
    sheet.addRule("h1, h2, h3, h4 { margin-top: 14px; margin-bottom: 8px; color: " + Theme.hex(p.emphasis) + "; }");
    sheet.addRule("h1 { font-size: 19pt; } h2 { font-size: 17pt; } h3 { font-size: 15pt; } h4 { font-size: 14pt; }");
    sheet.addRule("b, strong { color: " + Theme.hex(p.emphasis) + "; }");
    sheet.addRule("ul, ol { margin-top: 0; margin-bottom: 9px; margin-left: 20px; }");
    sheet.addRule("li { margin-bottom: 3px; }");
    sheet.addRule(
        "code { font-family: 'JetBrains Mono'; font-size: 12.5pt; background-color: "
            + Theme.hex(Theme.over(p.hover, p.bg))
            + "; color: "
            + Theme.hex(p.emphasis)
            + "; }");
    sheet.addRule(
        "pre { font-family: 'JetBrains Mono'; font-size: 12pt; background-color: "
            + Theme.hex(p.code)
            + "; border: 1px solid "
            + Theme.hex(Theme.over(p.border, p.bg))
            + "; padding: 12px; margin-top: 2px; margin-bottom: 12px; }");
    sheet.addRule("pre code { background-color: " + Theme.hex(p.code) + "; color: " + Theme.hex(p.text) + "; }");
    sheet.addRule("a { color: " + Theme.hex(p.accent) + "; }");
    sheet.addRule("blockquote { margin-left: 2px; padding-left: 12px; border-left: 2px solid " + Theme.hex(Theme.over(p.borderStrong, p.bg)) + "; color: " + Theme.hex(p.muted) + "; }");
    sheet.addRule("table { border-collapse: collapse; margin-bottom: 12px; }");
    sheet.addRule("th { color: " + Theme.hex(p.emphasis) + "; text-align: left; }");
    sheet.addRule("th, td { border-bottom: 1px solid " + Theme.hex(Theme.over(p.border, p.bg)) + "; padding: 6px 10px 6px 0; }");
    pane.setText("<html><body>" + html(markdown) + "</body></html>");
    pane.setEditable(false);
    pane.setOpaque(false);
    pane.setBorder(null);
    pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
    pane.setCaretPosition(0);
    return pane;
  }
}

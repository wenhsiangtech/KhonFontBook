package dev.tungwenblog.khonfontbook;

import static org.junit.jupiter.api.Assertions.*;

import com.helger.css.decl.CSSStyleRule;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderSettings;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FontCssTest {
    @Test void ttfObliqueAngleRoundTripsWithoutIncorrectFormatHint() {
        var spec = new FontRule.RuleSpec(); spec.setSelector(".sample");
        var css = FontCss.compile(List.of(new FontCss.Rule("ttf", spec, new FontCss.Target(
            List.of(new FontCss.Face("face", "hash", "/upload/font.ttf", 400, "oblique 12deg",
                List.of(new FontMetadata.CoverageRange(65, 90)))), List.of("sans-serif")))));
        var ast = CSSReader.readFromStringReader(css, new CSSReaderSettings().setBrowserCompliantMode(false));
        assertNotNull(ast);
        assertEquals("oblique 12deg", ast.getFontFaceRuleAtIndex(0).getDeclarationOfPropertyName("font-style").getExpressionAsCSSString());
        assertTrue(css.contains("font.ttf")); assertFalse(css.contains("woff2"));
    }
    @Test void supportedSelectorsRoundTripIncludingUnicodeAndEscapes() {
        for (var input : List.of(".post-title", "article > h1, .正文 #标题", "h2 + p ~ p a", "#section-introduction", ".\\31 23", ".\\@at", ".\\*literal", ".\\|literal", ".\\:pseudo", ".a/* comment */ > .b")) {
            var canonical = FontCss.canonicalSelector(input);
            assertFalse(canonical.isBlank(), input);
            assertEquals(canonical, FontCss.canonicalSelector(canonical));
        }
    }
    @Test void rejectsUnsupportedSyntaxInjectionAndParserRecovery() {
        for (var input : List.of("", ".bad[", "h1; color:red", ".a{color:red}.b", "@import url(x); .a", "@charset \"UTF-8\"; .a", "@\\63harset \"UTF-8\"; .a", "<!-- .a -->", ".a {} .b",
            ".a { font-family: kfb-probe; } /*", ".a/*", ".a:hover", "h1::before", "[class]", "*", "svg|a", "|a",
            "& .a", ":has(p)", ".a >", "> .a", ".a > + .b", ".a,", ".a\u0000.b", ".a { --x: 1; } .b",
            ".a}" , ".a;", ".a \\{ color:red", ".x".repeat(33), ".x".repeat(513), ".a,".repeat(16) + ".b")) {
            assertThrows(ResponseStatusException.class, () -> FontCss.selectors(input), input);
        }
    }
    private FontCss.Rule rule(Integer weight, boolean force, String hash) {
        var spec = new FontRule.RuleSpec(); spec.setSelector(".post-title, #stable-heading"); spec.setWeight(weight); spec.setForce(force);
        return new FontCss.Rule("rule-fixed", spec, new FontCss.Target(List.of(new FontCss.Face("face-fixed", hash,
            "/upload/quote\"and\\slash.woff2", 400, "italic", List.of(new FontMetadata.CoverageRange(65, 90)))),
            List.of("A \"quoted\" font", "sans-serif")));
    }
    @Test void astWriterSafelyQuotesNamesAndUrlsAndOnlyWritesExplicitProperties() {
        var css = FontCss.compile(List.of(rule(null, false, "hash1")));
        var ast = CSSReader.readFromStringReader(css, new CSSReaderSettings().setBrowserCompliantMode(false));
        assertNotNull(ast); assertEquals(1, ast.getFontFaceRuleCount()); assertEquals(1, ast.getStyleRuleCount());
        CSSStyleRule style = ast.getStyleRuleAtIndex(0);
        assertEquals(2, style.getDeclarationCount()); assertNull(style.getDeclarationOfPropertyName("font-weight"));
        assertNull(style.getDeclarationOfPropertyName("font-style")); assertFalse(css.contains("!important"));
        assertEquals("400", ast.getFontFaceRuleAtIndex(0).getDeclarationOfPropertyName("font-weight").getExpressionAsCSSString());
        assertEquals("italic", ast.getFontFaceRuleAtIndex(0).getDeclarationOfPropertyName("font-style").getExpressionAsCSSString());
        assertTrue(css.contains("U+41-5a") || css.contains("U+41-5A"));
        assertEquals(css, FontCss.compile(List.of(rule(null, false, "hash1"))));
        assertNotEquals(FontService.hash(css.getBytes(StandardCharsets.UTF_8)), FontService.hash(FontCss.compile(List.of(rule(null, false, "hash2"))).getBytes(StandardCharsets.UTF_8)));
        var explicit = CSSReader.readFromStringReader(FontCss.compile(List.of(rule(400, true, "hash1"))), new CSSReaderSettings());
        var forced = explicit.getStyleRuleAtIndex(0);
        assertTrue(forced.getDeclarationOfPropertyName("font-family").isImportant());
        assertTrue(forced.getDeclarationOfPropertyName("font-weight").isImportant());
        assertFalse(forced.getDeclarationAtIndex(0).isImportant());
    }
}

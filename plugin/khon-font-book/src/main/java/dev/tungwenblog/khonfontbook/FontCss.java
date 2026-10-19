package dev.tungwenblog.khonfontbook;

import static dev.tungwenblog.khonfontbook.FontParser.problem;

import com.helger.css.decl.*;
import com.helger.css.reader.CSSReader;
import com.helger.css.reader.CSSReaderSettings;
import com.helger.css.reader.errorhandler.CollectingCSSParseErrorHandler;
import com.helger.css.reader.errorhandler.ICSSInterpretErrorHandler;
import com.helger.css.parser.CSSCharStream;
import com.helger.css.parser.ParserCSS30Constants;
import com.helger.css.parser.ParserCSS30TokenManager;
import com.helger.css.writer.CSSWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;

/** The supported selector subset and AST writer, shared by validation and compilation. */
final class FontCss {
    static final String VERSION = "kfb-css-v2-ph-css-8.2.3";
    private FontCss() { }

    static CSSStyleRule selectors(String input) {
        if (input == null || input.isBlank() || input.length() > 1024) { throw invalid(); }
        var errors = new CollectingCSSParseErrorHandler();
        var failed = new AtomicBoolean();
        var settings = new CSSReaderSettings().setBrowserCompliantMode(false)
            .setCustomErrorHandler(errors).setCustomExceptionHandler(ex -> failed.set(true))
            .setInterpretErrorHandler(new ICSSInterpretErrorHandler() {
                public void onCSSInterpretationWarning(String message) { failed.set(true); }
                public void onCSSInterpretationError(String message) { failed.set(true); }
            });
        try {
            // Some legal stylesheet constructs (notably @charset) disappear in the AST.
            // Use the library lexer to reject such input before adding our fixed declaration.
            var tokens = new ParserCSS30TokenManager(new CSSCharStream(new java.io.StringReader(input)));
            tokens.setCustomErrorHandler(errors);
            for (var token = tokens.getNextToken(); token.kind != ParserCSS30Constants.EOF; token = tokens.getNextToken()) {
                if (token.image.startsWith("@") || token.kind == ParserCSS30Constants.LBRACE
                    || token.kind == ParserCSS30Constants.RBRACE || token.kind == ParserCSS30Constants.SEMICOLON
                    || token.kind == ParserCSS30Constants.CDO || token.kind == ParserCSS30Constants.CDC
                    || token.kind == ParserCSS30Constants.ASTERISK || "|".equals(token.image)) { throw invalid(); }
            }
            var css = CSSReader.readFromStringReader(input + " { font-family: kfb-probe; }", settings);
            if (failed.get() || errors.hasParseErrors() || css == null || css.getRuleCount() != 1
                || css.hasImportRules() || css.hasNamespaceRules()
                || !(css.getRuleAtIndex(0) instanceof CSSStyleRule rule) || rule.hasRules()
                || rule.getDeclarationCount() != 1 || rule.getSelectorCount() < 1 || rule.getSelectorCount() > 16) { throw invalid(); }
            var declaration = rule.getDeclarationAtIndex(0);
            if (!"font-family".equals(declaration.getProperty()) || declaration.isImportant()
                || !"kfb-probe".equals(declaration.getExpressionAsCSSString())) { throw invalid(); }
            for (var selector : rule.getAllSelectors()) {
                if (selector.getMemberCount() < 1 || selector.getMemberCount() > 32) { throw invalid(); }
                boolean combinator = true;
                for (var member : selector.getAllMembers()) {
                    if (member instanceof ECSSSelectorCombinator) {
                        if (combinator) { throw invalid(); }
                        combinator = true;
                    } else if (member instanceof CSSSelectorSimpleMember simple
                        && !simple.isPseudo() && !simple.isNesting()) {
                        combinator = false;
                    } else { throw invalid(); }
                }
                if (combinator) { throw invalid(); }
            }
            rule.removeAllDeclarations();
            return rule;
        } catch (org.springframework.web.server.ResponseStatusException ex) { throw ex; }
        catch (RuntimeException ex) { throw invalid(); }
    }

    static String canonicalSelector(String input) {
        var writer = writer();
        return selectors(input).getSelectorsAsCSSString(writer.getSettings(), 0);
    }

    record Face(String id, String contentHash, String url, int weight, String style,
        List<FontMetadata.CoverageRange> ranges) { }
    record Target(List<Face> faces, List<String> fallbacks) { }
    record Rule(String id, FontRule.RuleSpec spec, Target target) { }

    static String token(Rule entry) {
        return FontService.hash((VERSION + entry.id() + entry.target().faces().stream()
            .map(Face::contentHash).toList()).getBytes(StandardCharsets.UTF_8));
    }

    static String compile(List<Rule> rules) {
        var sheet = new CascadingStyleSheet();
        for (var entry : rules) {
            // The name is derived from validated content, not display names or user CSS.
            String token = token(entry);
            String name = "kfb-" + token;
            String variable = "--kfb-" + token;
            for (var face : entry.target().faces()) {
                if (face.weight() < 1 || face.weight() > 1000 || !FontFormat.validStyle(face.style())
                    || face.ranges().isEmpty()) { throw invalid(); }
                var font = new CSSFontFaceRule();
                font.addDeclaration(new CSSDeclaration("font-family", CSSExpression.createString(name)));
                // Sources are validated WOFF2 or TTF. Omit the optional format
                // hint so the browser reads the actual file without a false hint.
                var src = CSSExpression.createURI(face.url());
                font.addDeclaration(new CSSDeclaration("src", src));
                font.addDeclaration(new CSSDeclaration("font-weight", CSSExpression.createNumber(face.weight())));
                font.addDeclaration(new CSSDeclaration("font-style", CSSExpression.createSimple(face.style())));
                font.addDeclaration(new CSSDeclaration("font-display", CSSExpression.createSimple("swap")));
                var ranges = new CSSExpression();
                for (var range : face.ranges()) {
                    if (range.start() < 0 || range.end() > 0x10ffff || range.start() > range.end()) { throw invalid(); }
                    if (ranges.getMemberCount() > 0) { ranges.addMember(ECSSExpressionOperator.COMMA); }
                    ranges.addTermSimple("U+" + Integer.toHexString(range.start()) + "-" + Integer.toHexString(range.end()));
                }
                font.addDeclaration(new CSSDeclaration("unicode-range", ranges)); sheet.addRule(font);
            }
            var family = CSSExpression.createString(name);
            var fallback = entry.target().fallbacks();
            if (fallback.isEmpty() || !List.of("serif", "sans-serif", "monospace", "system-ui").contains(fallback.getLast())) { throw invalid(); }
            for (int i = 0; i < fallback.size(); i++) {
                family.addMember(ECSSExpressionOperator.COMMA);
                if (i == fallback.size() - 1) { family.addTermSimple(fallback.get(i)); }
                else { family.addString(fallback.get(i)); }
            }
            // Variables are local to the matching elements; no :root mutation or Console application.
            var style = selectors(entry.spec().getSelector());
            style.addDeclaration(new CSSDeclaration(variable, family));
            style.addDeclaration(new CSSDeclaration("font-family", new CSSExpression().addMember(
                new CSSExpressionMemberFunction("var", CSSExpression.createSimple(variable))), entry.spec().isForce()));
            if (entry.spec().getWeight() != null) {
                style.addDeclaration(new CSSDeclaration("font-weight", CSSExpression.createNumber(entry.spec().getWeight()), entry.spec().isForce()));
            }
            sheet.addRule(style);
        }
        return writer().getCSSAsString(sheet);
    }

    private static CSSWriter writer() { return new CSSWriter(false).setWriteHeaderText(false).setWriteFooterText(false); }
    private static org.springframework.web.server.ResponseStatusException invalid() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_SELECTOR", "仅支持 type/class/ID、后代、> + ~ 和逗号列表；不接受属性、伪类、通配符、namespace 或 CSS 片段（1024 字符、16 选择器、各 32 节点）");
    }
}

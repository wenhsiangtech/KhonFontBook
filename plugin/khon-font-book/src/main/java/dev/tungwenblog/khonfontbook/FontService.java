package dev.tungwenblog.khonfontbook;

import static dev.tungwenblog.khonfontbook.FontParser.problem;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.Theme;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.attachment.endpoint.SimpleFilePart;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.plugin.SettingFetcher;
import run.halo.app.infra.ExternalUrlSupplier;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class FontService {
    private final ExtensionClient client;
    private final AttachmentService attachments;
    private final FontParser parser;
    private final SettingFetcher settings;
    private final Environment environment;
    private final ExternalUrlSupplier externalUrl;
    private final FontStorage storage;
    private static final JsonMapper JSON = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private final WebClient http = WebClient.builder().codecs(c -> c.defaultCodecs().maxInMemorySize(FontParser.MAX_BYTES)).build();
    private final java.util.concurrent.atomic.AtomicBoolean parsingRequest = new java.util.concurrent.atomic.AtomicBoolean();

    private <T> T parsingRequest(java.util.function.Supplier<T> action) {
        if (!parsingRequest.compareAndSet(false, true))
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "PARSER_BUSY", "解析器忙，请稍后重试");
        try { return action.get(); }
        finally { parsingRequest.set(false); }
    }

    public record Options(String policyName, String parserExecutable) { }
    public record AttachmentChoice(String name, String displayName, long size) { }
    public record FaceView(FontFace face, String availability, String message) { }
    public record Catalog(List<FontFamily> families, List<FontAsset> assets, List<FaceView> faces,
        List<AttachmentChoice> attachments, FontParser.RuntimeInfo runtime, String policyName,
        String storageMessage, int page, int size, int total) { }
    public record ImportResult(FontFace face, boolean duplicate, String message) { }
    public record FamilyUpdate(Long version, String displayName, boolean enabled) { }
    public record FaceUpdate(Long version, String familyId, boolean enabled) { }
    public record AssetUpdate(Long version, String source, String license, String category) { }
    public record ImportFields(String familyId, String source, String license, String category) { }
    public record ExistingImport(String attachmentName, String familyId, String source, String license, String category) { }
    public record Reference(String kind, String id, String displayName) { }
    public record References(List<Reference> items, String boundary) { }
    public record Variant(int weight, String style) { }
    public record FamilyOption(FontFamily family, List<Variant> latinVariants, List<Variant> cjkVariants) { }
    public record SchemeUpdate(Long version, String displayName, boolean enabled, String latinFamilyId,
        String cjkFamilyId, String rangePolicy, int weight, String style, List<String> fallbacks) { }
    public record SchemeView(FontScheme scheme, List<Variant> variants, List<String> diagnostics, boolean usable) { }
    public record SchemeCatalog(List<SchemeView> items, List<FamilyOption> families, int page, int size, int total) { }
    public record PreviewFace(String faceId, String role, int weight, String style,
        List<FontMetadata.CoverageRange> unicodeRanges) { }
    public record SchemePreview(String schemeId, String revision, boolean enabled, int weight, String style, List<String> fallbacks,
        List<PreviewFace> faces, List<String> diagnostics) { }

    public record RuleUpdate(Long version, String displayName, boolean enabled, String themeName,
        FontRule.Scope scope, String contentName, String selector, String targetKind, String targetId,
        Integer weight, int order, boolean force) { }
    public record Choice(String id, String label) { }
    public record TargetChoice(String kind, String id, String label, boolean enabled, List<Integer> weights) { }
    public record RuleOptions(List<Choice> themes, List<TargetChoice> targets, List<Choice> contents,
        String contentKind, int page, int size, int total) { }
    public record RuleView(FontRule rule, List<String> diagnostics) { }
    public record RuleCatalog(List<RuleView> items, int page, int size, int total) { }
    /** P5 must construct this from resolved theme + trusted model GVK/name, never query parameters. */
    public record CompileContext(String themeName, String contentKind, String contentName) { }
    public record CompiledCss(String compilerVersion, String sha256, String css, List<String> ruleIds,
        List<String> diagnostics, String matching) { }

    public synchronized RuleOptions ruleOptions(String kind, int page, int size, String keyword) {
        checkPage(page, size);
        if (!List.of("Post", "SinglePage").contains(Objects.toString(kind, ""))) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_CONTEXT", "内容类型须为 Post 或 SinglePage");
        }
        var themes = all(Theme.class).stream().map(t -> new Choice(t.getMetadata().getName(), t.getSpec().getDisplayName())).toList();
        var targets = new ArrayList<TargetChoice>();
        all(FontFamily.class).forEach(f -> targets.add(new TargetChoice("family", f.getMetadata().getName(),
            f.getSpec().getDisplayName(), f.getSpec().isEnabled(), familyTarget(f.getMetadata().getName(), null).faces().stream()
                .map(FontCss.Face::weight).distinct().sorted().toList())));
        all(FontScheme.class).forEach(s -> targets.add(new TargetChoice("scheme", s.getMetadata().getName(),
            s.getSpec().getDisplayName(), s.getSpec().isEnabled(), !s.getSpec().isEnabled()
                || !commonVariants(s.getSpec().getLatinFamilyId(), s.getSpec().getCjkFamilyId()).contains(new Variant(s.getSpec().getWeight(), s.getSpec().getStyle()))
                ? List.of() : List.of(s.getSpec().getWeight()))));
        List<Choice> contents = "Post".equals(kind)
            ? all(Post.class).stream().filter(this::publicPost).map(p -> new Choice(p.getMetadata().getName(), p.getSpec().getTitle())).toList()
            : all(SinglePage.class).stream().filter(this::publicPage).map(p -> new Choice(p.getMetadata().getName(), p.getSpec().getTitle())).toList();
        contents = contents.stream().filter(c -> keyword == null || keyword.isBlank()
            || c.label().toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT)) || c.id().contains(keyword)).toList();
        int from = (int) Math.min((long) (page - 1) * size, contents.size());
        return new RuleOptions(themes, targets, contents.subList(from, Math.min(from + size, contents.size())), kind, page, size, contents.size());
    }

    public synchronized RuleCatalog rules(int page, int size, String keyword) {
        checkPage(page, size);
        var rules = orderedRules().stream().filter(r -> keyword == null || keyword.isBlank()
            || r.getSpec().getDisplayName().contains(keyword) || r.getSpec().getSelector().contains(keyword)).toList();
        int from = (int) Math.min((long) (page - 1) * size, rules.size());
        return new RuleCatalog(rules.subList(from, Math.min(from + size, rules.size())).stream().map(r -> {
            var diagnostics = new ArrayList<String>();
            try { validateRuleContext(r.getSpec()); } catch (org.springframework.web.server.ResponseStatusException ex) { diagnostics.add(ex.getReason()); }
            try { if (ruleTarget(r.getSpec(), diagnostics).faces().isEmpty()) { diagnostics.add("目标停用、资源缺失或所选字重不可用，编译时跳过此规则"); } }
            catch (org.springframework.web.server.ResponseStatusException ex) { diagnostics.add(ex.getReason()); }
            return new RuleView(r, List.copyOf(diagnostics));
        }).toList(), page, size, rules.size());
    }

    public synchronized FontRule saveRule(String id, RuleUpdate request) {
        FontRule rule;
        if (id == null) { rule = new FontRule(); rule.setMetadata(identity("rule")); }
        else { rule = rule(id); version(rule, request.version()); }
        var spec = new FontRule.RuleSpec();
        spec.setDisplayName(text(request.displayName(), 160, true)); spec.setEnabled(request.enabled());
        spec.setThemeName(text(request.themeName(), 100, true));
        if (request.scope() == null || !List.of("family", "scheme").contains(Objects.toString(request.targetKind(), ""))
            || request.weight() != null && (request.weight() < 1 || request.weight() > 1000)
            || Math.abs((long) request.order()) > 1000000) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_RULE", "范围、目标类型、字重或顺序无效（顺序限 ±1000000）");
        }
        spec.setScope(request.scope()); spec.setContentName(text(request.contentName(), 100, false));
        boolean one = spec.getScope() == FontRule.Scope.POST_ONE || spec.getScope() == FontRule.Scope.PAGE_ONE;
        if (one == spec.getContentName().isBlank()) { throw problem(HttpStatus.BAD_REQUEST, "INVALID_CONTEXT", "指定文章/页面须有稳定内容 ID，其他范围不能带内容 ID"); }
        spec.setSelector(FontCss.canonicalSelector(request.selector()));
        spec.setTargetKind(request.targetKind()); spec.setTargetId(text(request.targetId(), 100, true));
        spec.setWeight(request.weight()); spec.setOrder(request.order()); spec.setForce(request.force());
        // A damaged existing rule can be disabled without recreating missing resources.
        var old = rule.getSpec();
        if (spec.isEnabled() || old == null || !Objects.equals(old.getThemeName(), spec.getThemeName())
            || old.getScope() != spec.getScope() || !Objects.equals(old.getContentName(), spec.getContentName())) { validateRuleContext(spec); }
        if (spec.isEnabled() || old == null || !Objects.equals(old.getTargetKind(), spec.getTargetKind())
            || !Objects.equals(old.getTargetId(), spec.getTargetId())) {
            if ("family".equals(spec.getTargetKind())) { family(spec.getTargetId()); } else { scheme(spec.getTargetId()); }
        }
        if (spec.isEnabled()) {
            var target = ruleTarget(spec, new ArrayList<>());
            if (target.faces().isEmpty()) { throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "RULE_TARGET_UNAVAILABLE", "目标停用、缺失或未提供所选真实字重，无法启用；可先停用保存"); }
        }
        rule.setSpec(spec);
        if (id == null) { client.create(rule); } else { client.update(rule); }
        return rule(rule.getMetadata().getName());
    }

    public synchronized CompiledCss compile(CompileContext context) {
        if (context == null || context.themeName() == null) { throw problem(HttpStatus.BAD_REQUEST, "INVALID_CONTEXT", "需要已解析主题"); }
        theme(context.themeName());
        var kind = Objects.toString(context.contentKind(), "Unknown");
        var name = Objects.toString(context.contentName(), "");
        if (!List.of("Unknown", "Home", "Post", "SinglePage").contains(kind) || List.of("Unknown", "Home").contains(kind) && !name.isBlank()) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_CONTEXT", "仅支持 Unknown、Home、Post 和 SinglePage；主页及其他主题页不能带内容 ID");
        }
        if ("Post".equals(kind)) { requirePublicContent("Post", name); }
        if ("SinglePage".equals(kind)) { requirePublicContent("SinglePage", name); }
        var output = new ArrayList<FontCss.Rule>();
        var diagnostics = new ArrayList<String>();
        for (var rule : orderedRules()) {
            var spec = rule.getSpec();
            if (!spec.isEnabled() || !context.themeName().equals(spec.getThemeName()) || !matches(spec, kind, name)) { continue; }
            try {
                var notes = new ArrayList<String>();
                var target = ruleTarget(spec, notes);
                notes.forEach(n -> diagnostics.add(rule.getMetadata().getName() + ": " + n));
                if (target.faces().isEmpty()) { diagnostics.add(rule.getMetadata().getName() + ": 无可用字体，跳过规则，保留主题表现"); continue; }
                FontCss.selectors(spec.getSelector());
                output.add(new FontCss.Rule(rule.getMetadata().getName(), spec, target));
            } catch (org.springframework.web.server.ResponseStatusException ex) { diagnostics.add(rule.getMetadata().getName() + ": " + ex.getReason()); }
        }
        var css = FontCss.compile(output);
        return new CompiledCss(FontCss.VERSION, hash(css.getBytes(java.nio.charset.StandardCharsets.UTF_8)), css,
            output.stream().map(FontCss.Rule::id).toList(), List.copyOf(diagnostics), "仅验证语法和上下文编译；未读取 DOM，实际选择器匹配与页面生效未经验证");
    }

    private static boolean matches(FontRule.RuleSpec rule, String kind, String name) {
        return switch (rule.getScope()) {
            case THEME_ALL -> true;
            case HOME -> "Home".equals(kind);
            case POST_ALL -> "Post".equals(kind);
            case POST_ONE -> "Post".equals(kind) && name.equals(rule.getContentName());
            case PAGE_ONE -> "SinglePage".equals(kind) && name.equals(rule.getContentName());
        };
    }
    private FontCss.Target ruleTarget(FontRule.RuleSpec spec, List<String> diagnostics) {
        if ("family".equals(spec.getTargetKind())) { return familyTarget(spec.getTargetId(), spec.getWeight()); }
        if (!"scheme".equals(spec.getTargetKind())) { throw problem(HttpStatus.BAD_REQUEST, "INVALID_RULE", "未知目标类型"); }
        var plan = buildSchemePreview(scheme(spec.getTargetId())); diagnostics.addAll(plan.diagnostics());
        if (spec.getWeight() != null && (spec.getWeight() != plan.weight()
            || plan.faces().stream().noneMatch(f -> "latin".equals(f.role()))
            || plan.faces().stream().noneMatch(f -> "cjk".equals(f.role())))) { return new FontCss.Target(List.of(), plan.fallbacks()); }
        var faces = plan.faces().stream().map(f -> cssFace(face(f.faceId()), f.unicodeRanges())).toList();
        return new FontCss.Target(faces, plan.fallbacks());
    }
    private FontCss.Target familyTarget(String id, Integer weight) {
        var family = family(id);
        var faces = new ArrayList<FontCss.Face>();
        if (family.getSpec().isEnabled()) {
            var claimed = new java.util.HashMap<Variant, java.util.BitSet>();
            for (var face : all(FontFace.class)) {
                var fs = face.getSpec(); var meta = fs.getMetadata();
                if (!id.equals(fs.getFamilyId()) || !fs.isEnabled() || weight != null && weight != meta.weight()) { continue; }
                try {
                    var asset = asset(fs.getAssetId());
                    if (!"READY".equals(asset.getStatus().getState())) { continue; }
                    var points = new java.util.BitSet(0x110000);
                    for (var range : meta.coverageRanges()) { points.set(range.start(), range.end() + 1); }
                    var used = claimed.computeIfAbsent(new Variant(meta.weight(), meta.style()), k -> new java.util.BitSet(0x110000));
                    points.andNot(used);
                    var compiled = cssFace(face, SchemeRanges.ranges(points));
                    if (!points.isEmpty()) { faces.add(compiled); used.or(points); }
                } catch (org.springframework.web.server.ResponseStatusException ex) { /* An unavailable subset falls back. */ }
            }
        }
        return new FontCss.Target(List.copyOf(faces), List.of("sans-serif"));
    }
    private FontCss.Face cssFace(FontFace face, List<FontMetadata.CoverageRange> ranges) {
        var asset = asset(face.getSpec().getAssetId()); var a = attachment(asset.getSpec().getAttachmentName());
        safeUri(a);
        return new FontCss.Face(face.getMetadata().getName(), asset.getSpec().getSha256(), a.getStatus().getPermalink(),
            face.getSpec().getMetadata().weight(), face.getSpec().getMetadata().style(), ranges);
    }
    private List<FontRule> orderedRules() {
        return all(FontRule.class).stream().sorted(java.util.Comparator.comparingInt((FontRule r) -> r.getSpec().getOrder())
            .thenComparing(r -> r.getMetadata().getName())).toList();
    }
    private Theme theme(String name) { return client.fetch(Theme.class, Objects.toString(name, ""))
        .filter(t -> t.getMetadata().getDeletionTimestamp() == null)
        .orElseThrow(() -> problem(HttpStatus.BAD_REQUEST, "THEME_MISSING", "目标主题不存在")); }
    private boolean publicPost(Post p) { return !p.isDeleted() && p.isPublished() && Post.isPublic(p.getSpec()); }
    private boolean publicPage(SinglePage p) { return !Boolean.TRUE.equals(p.getSpec().getDeleted()) && p.isPublished()
        && p.getSpec().getVisible() == Post.VisibleEnum.PUBLIC; }
    private void requirePublicContent(String kind, String name) {
        boolean exists = "Post".equals(kind) ? client.fetch(Post.class, name).filter(p -> p.getMetadata().getDeletionTimestamp() == null).filter(this::publicPost).isPresent()
            : client.fetch(SinglePage.class, name).filter(p -> p.getMetadata().getDeletionTimestamp() == null).filter(this::publicPage).isPresent();
        if (!exists) { throw problem(HttpStatus.BAD_REQUEST, "CONTENT_UNAVAILABLE", "内容不存在、未发布或非公开；首版不编译私有/草稿专用样式"); }
    }
    private void validateRuleContext(FontRule.RuleSpec spec) {
        theme(spec.getThemeName());
        if (spec.getScope() == FontRule.Scope.POST_ONE) { requirePublicContent("Post", spec.getContentName()); }
        if (spec.getScope() == FontRule.Scope.PAGE_ONE) { requirePublicContent("SinglePage", spec.getContentName()); }
    }
    private FontRule rule(String id) { return client.fetch(FontRule.class, Objects.toString(id, ""))
        .filter(r -> r.getMetadata().getDeletionTimestamp() == null)
        .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "规则不存在")); }
    private static void checkPage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) { throw problem(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页范围无效"); }
    }

    public synchronized SchemeCatalog schemes(int page, int size, String keyword) {
        if (page < 1 || size < 1 || size > 100) { throw problem(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页范围无效"); }
        var values = all(FontScheme.class).stream().filter(s -> keyword == null || keyword.isBlank()
            || s.getSpec().getDisplayName().toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT))).toList();
        int from = (int) Math.min((long) (page - 1) * size, values.size());
        var items = values.subList(from, Math.min(from + size, values.size())).stream().map(s -> {
            var plan = buildSchemePreview(s);
            return new SchemeView(s, commonVariants(s.getSpec().getLatinFamilyId(), s.getSpec().getCjkFamilyId()),
                plan.diagnostics(), plan.faces().stream().anyMatch(f -> f.role().equals("latin"))
                    && plan.faces().stream().anyMatch(f -> f.role().equals("cjk")));
        }).toList();
        var families = all(FontFamily.class).stream().map(f -> new FamilyOption(f,
            variants(f.getMetadata().getName(), "latin"), variants(f.getMetadata().getName(), "cjk"))).toList();
        return new SchemeCatalog(items, families, page, size, values.size());
    }

    public synchronized FontScheme saveScheme(String id, SchemeUpdate request) {
        FontScheme value;
        if (id == null) { value = new FontScheme(); value.setMetadata(identity("scheme")); }
        else { value = scheme(id); version(value, request.version()); }
        var spec = new FontScheme.SchemeSpec();
        spec.setDisplayName(text(request.displayName(), 160, true));
        spec.setEnabled(request.enabled());
        spec.setLatinFamilyId(text(request.latinFamilyId(), 100, true));
        spec.setCjkFamilyId(text(request.cjkFamilyId(), 100, true));
        if (!SchemeRanges.POLICY.equals(request.rangePolicy()) || request.weight() < 1 || request.weight() > 1000
            || !FontFormat.validStyle(request.style())) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_SCHEME", "字符策略、字重或样式不受支持");
        }
        spec.setRangePolicy(SchemeRanges.POLICY); spec.setWeight(request.weight()); spec.setStyle(request.style());
        spec.setFallbacks(validateFallbacks(request.fallbacks()));
        // A disabled damaged scheme may retain unchanged missing IDs, so it can be turned off safely.
        if (request.enabled() || id == null || !spec.getLatinFamilyId().equals(value.getSpec().getLatinFamilyId())) { family(spec.getLatinFamilyId()); }
        if (request.enabled() || id == null || !spec.getCjkFamilyId().equals(value.getSpec().getCjkFamilyId())) { family(spec.getCjkFamilyId()); }
        if (request.enabled() && !commonVariants(spec.getLatinFamilyId(), spec.getCjkFamilyId())
            .contains(new Variant(spec.getWeight(), spec.getStyle()))) {
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "SCHEME_VARIANT_UNAVAILABLE", "两侧须有已启用、附件记录存在且覆盖对应字符范围的相同真实字重/样式；可先停用保存");
        }
        value.setSpec(spec);
        if (id == null) { client.create(value); } else { client.update(value); }
        return scheme(value.getMetadata().getName());
    }

    private static List<String> validateFallbacks(List<String> values) {
        var generic = List.of("serif", "sans-serif", "monospace", "system-ui");
        if (values == null || values.isEmpty() || values.size() > 9) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_FALLBACK", "回退链需要 1 至 9 项，最后一项为通用字体族");
        }
        var result = new ArrayList<String>();
        for (int i = 0; i < values.size(); i++) {
            var name = text(values.get(i), 80, true);
            if (i == values.size() - 1 ? !generic.contains(name)
                : generic.contains(name) || !name.matches("[\\p{L}\\p{N} ._-]+")) {
                throw problem(HttpStatus.BAD_REQUEST, "INVALID_FALLBACK", "回退项仅接受字体名称，最后一项为 serif/sans-serif/monospace/system-ui；不接受 CSS 文本");
            }
            if (result.contains(name)) { throw problem(HttpStatus.BAD_REQUEST, "INVALID_FALLBACK", "回退链不能重复"); }
            result.add(name);
        }
        return List.copyOf(result);
    }

    private List<Variant> variants(String familyId, String role) {
        var family = client.fetch(FontFamily.class, familyId).filter(f -> f.getMetadata().getDeletionTimestamp() == null);
        if (family.isEmpty() || !family.get().getSpec().isEnabled()) { return List.of(); }
        return all(FontFace.class).stream().filter(f -> familyId.equals(f.getSpec().getFamilyId()) && f.getSpec().isEnabled())
            .filter(f -> {
                try {
                    var asset = asset(f.getSpec().getAssetId());
                    safeUri(attachment(asset.getSpec().getAttachmentName()));
                    return "READY".equals(asset.getStatus().getState())
                        && !SchemeRanges.coverage(f.getSpec().getMetadata().coverageRanges(), role).isEmpty();
                } catch (Exception ex) { return false; }
            }).map(f -> new Variant(f.getSpec().getMetadata().weight(), f.getSpec().getMetadata().style()))
            .distinct().sorted(java.util.Comparator.comparingInt(Variant::weight).thenComparing(Variant::style)).toList();
    }

    private List<Variant> commonVariants(String latinId, String cjkId) {
        var cjk = variants(cjkId, "cjk");
        return variants(latinId, "latin").stream().filter(cjk::contains).toList();
    }

    public synchronized SchemePreview schemePreview(String id) { return buildSchemePreview(scheme(id)); }

    private SchemePreview buildSchemePreview(FontScheme value) {
        var spec = value.getSpec();
        var diagnostics = new ArrayList<String>();
        var faces = new ArrayList<PreviewFace>();
        var fingerprint = new ArrayList<Object>();
        fingerprint.add("scheme-preview-v1"); fingerprint.add(value); fingerprint.add(options().policyName());
        if (!spec.isEnabled()) { diagnostics.add("方案已停用，仅使用回退链，不加载字体"); }
        for (var role : List.of("latin", "cjk")) {
            String familyId = "latin".equals(role) ? spec.getLatinFamilyId() : spec.getCjkFamilyId();
            var claimed = new java.util.BitSet(0x110000);
            var family = client.fetch(FontFamily.class, familyId).filter(f -> f.getMetadata().getDeletionTimestamp() == null);
            fingerprint.add(family.orElse(null));
            boolean enabled = family.isPresent() && family.get().getSpec().isEnabled();
            if (!enabled) { diagnostics.add(role + " 字体族缺失或停用，对应字符使用回退链"); }
            for (var face : all(FontFace.class)) {
                var fs = face.getSpec();
                if (!familyId.equals(fs.getFamilyId())) { continue; }
                fingerprint.add(face);
                try {
                    var asset = asset(fs.getAssetId()); fingerprint.add(asset);
                    var a = attachment(asset.getSpec().getAttachmentName()); fingerprint.add(a);
                    safeUri(a);
                    if (!enabled || !spec.isEnabled() || fs.getMetadata().weight() != spec.getWeight()
                        || !fs.getMetadata().style().equals(spec.getStyle())) { continue; }
                    if (!fs.isEnabled() || !"READY".equals(asset.getStatus().getState())) {
                        diagnostics.add(role + " 字形停用或导入未完成：" + face.getMetadata().getName()); continue;
                    }
                    var points = SchemeRanges.coverage(fs.getMetadata().coverageRanges(), role);
                    // Stable face-ID priority handles overlaps while allowing multiple WOFF2 subsets.
                    points.andNot(claimed); claimed.or(points);
                    if (!points.isEmpty()) { faces.add(new PreviewFace(face.getMetadata().getName(), role,
                        spec.getWeight(), spec.getStyle(), SchemeRanges.ranges(points))); }
                } catch (org.springframework.web.server.ResponseStatusException ex) {
                    fingerprint.add(Objects.toString(ex.getReason(), "不可用"));
                    diagnostics.add(role + " 字形不可用：" + face.getMetadata().getName() + "；" + ex.getReason());
                }
            }
            if (spec.isEnabled() && claimed.isEmpty()) { diagnostics.add(role + " 没有当前真实字重/样式的可用字符，对应字符使用回退链"); }
        }
        var revision = hash(JSON.writeValueAsBytes(fingerprint));
        return new SchemePreview(value.getMetadata().getName(), revision, spec.isEnabled(), spec.getWeight(), spec.getStyle(), spec.getFallbacks(),
            List.copyOf(faces), List.copyOf(diagnostics));
    }

    public synchronized byte[] schemePreviewFont(String schemeId, String faceId, String revision) {
        var plan = schemePreview(schemeId);
        if (!plan.revision().equals(revision)) { throw problem(HttpStatus.CONFLICT, "PREVIEW_STALE", "方案或字体已变化，请重新加载方案预览"); }
        if (plan.faces().stream().noneMatch(f -> f.faceId().equals(faceId))) {
            throw problem(HttpStatus.CONFLICT, "SCHEME_FACE_UNAVAILABLE", "该字形不属于当前可用预览，可能已停用或移除");
        }
        return preview(faceId);
    }

    private FontScheme scheme(String id) { return client.fetch(FontScheme.class, Objects.requireNonNullElse(id, ""))
        .filter(s -> s.getMetadata().getDeletionTimestamp() == null)
        .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "SCHEME_NOT_FOUND", "字体方案不存在")); }

    private boolean usesFamily(FontScheme scheme, String familyId) {
        return familyId.equals(scheme.getSpec().getLatinFamilyId()) || familyId.equals(scheme.getSpec().getCjkFamilyId());
    }

    private void schemeReferences(List<Reference> refs, String familyId) {
        all(FontScheme.class).stream().filter(s -> usesFamily(s, familyId)).forEach(s -> refs.add(
            new Reference("scheme", s.getMetadata().getName(), s.getSpec().getDisplayName())));
    }

    private void ruleReferences(List<Reference> refs, String kind, String id) {
        all(FontRule.class).stream().filter(r -> kind.equals(r.getSpec().getTargetKind()) && id.equals(r.getSpec().getTargetId()))
            .forEach(r -> refs.add(new Reference("rule", r.getMetadata().getName(), r.getSpec().getDisplayName())));
    }


    public synchronized FontPublic.Families publicFamilies(int page, int size) {
        checkPage(page, size);
        var items = all(FontFamily.class).stream().filter(f -> f.getSpec().isEnabled())
            .map(f -> publicFamilyOrNull(f.getMetadata().getName())).filter(Objects::nonNull).toList();
        int from = (int) Math.min((long) (page - 1) * size, items.size());
        return new FontPublic.Families(items.subList(from, Math.min(from + size, items.size())), page, size, items.size());
    }
    public synchronized FontPublic.Family publicFamily(String id) {
        var value = publicFamilyOrNull(id);
        if (value == null) { throw problem(HttpStatus.NOT_FOUND, "PUBLIC_TARGET_UNAVAILABLE", "字体不存在、停用或无可发布字形"); }
        return value;
    }
    private FontPublic.Family publicFamilyOrNull(String id) {
        var f = family(id);
        if (!f.getSpec().isEnabled()) { return null; }
        var items = familyTarget(id, null).faces().stream().map(v -> {
            var face = face(v.id()); var m = face.getSpec().getMetadata(); var a = asset(face.getSpec().getAssetId()).getSpec();
            return new FontPublic.Face(v.id(), m.family(), m.subfamily(), m.weight(), m.style(), m.characterCount(),
                m.numGlyphs(), m.embeddingRights(), m.coverageRanges(), a.getSha256(), v.url(), a.getSource(), a.getLicense(), a.getCategory());
        }).toList();
        if (items.isEmpty()) { return null; }
        var value = new FontPublic.Family(id, f.getSpec().getDisplayName(), "", items, publicStylePath("family", id));
        return new FontPublic.Family(id, value.displayName(), hash(JSON.writeValueAsBytes(value)), items, value.stylePath());
    }
    public synchronized FontPublic.Schemes publicSchemes(int page, int size) {
        checkPage(page, size);
        var items = all(FontScheme.class).stream().map(s -> publicSchemeOrNull(s.getMetadata().getName())).filter(Objects::nonNull).toList();
        int from = (int) Math.min((long) (page - 1) * size, items.size());
        return new FontPublic.Schemes(items.subList(from, Math.min(from + size, items.size())), page, size, items.size());
    }
    public synchronized FontPublic.Scheme publicScheme(String id) {
        var value = publicSchemeOrNull(id);
        if (value == null) { throw problem(HttpStatus.NOT_FOUND, "PUBLIC_TARGET_UNAVAILABLE", "方案不存在、停用或两侧字体不可用"); }
        return value;
    }
    private FontPublic.Scheme publicSchemeOrNull(String id) {
        var s = scheme(id); var spec = s.getSpec(); var plan = buildSchemePreview(s);
        if (!spec.isEnabled() || plan.faces().stream().noneMatch(f -> "latin".equals(f.role()))
            || plan.faces().stream().noneMatch(f -> "cjk".equals(f.role()))) { return null; }
        // Public revision uses only publishable data, never management metadata or diagnostics.
        var fingerprint = List.of(spec.getDisplayName(), spec.getRangePolicy(), plan.faces(),
            plan.fallbacks(), publicFamily(spec.getLatinFamilyId()), publicFamily(spec.getCjkFamilyId()));
        return new FontPublic.Scheme(id, spec.getDisplayName(), hash(JSON.writeValueAsBytes(fingerprint)),
            spec.getLatinFamilyId(), spec.getCjkFamilyId(), spec.getRangePolicy(), spec.getWeight(), spec.getStyle(),
            spec.getFallbacks(), publicStylePath("scheme", id));
    }
    private static String publicStylePath(String kind, String id) {
        return "/apis/api.font-book.tungwenblog.dev/v1alpha1/" + ("family".equals(kind) ? "families" : "schemes") + "/" + id + "/style";
    }
    /** Fixed public class selector: no anonymous theme/content compiler or arbitrary CSS input. */
    synchronized FontCss.Rule publicStyleRule(String kind, String id) {
        if ("family".equals(kind)) { publicFamily(id); } else if ("scheme".equals(kind)) { publicScheme(id); }
        else { throw problem(HttpStatus.BAD_REQUEST, "INVALID_KIND", "公开目标仅支持 family/scheme"); }
        // IDs may have been written through a super-admin raw API; validate before making a class.
        if (!id.matches("(family|scheme)-[a-zA-Z0-9-]{1,90}")) {
            throw problem(HttpStatus.CONFLICT, "INVALID_STABLE_ID", "目标标识无法安全发布");
        }
        var spec = new FontRule.RuleSpec(); spec.setTargetKind(kind); spec.setTargetId(id);
        spec.setSelector(".kfb-" + id);
        return new FontCss.Rule("public-" + id, spec, ruleTarget(spec, new ArrayList<>()));
    }

    public record ExternalUpdate(Long version, String targetKind, String targetId, String contentRevision,
        String previousKind, String previousId, String confirmation) { }
    public record ExternalView(String sourceId, String key, long version, String targetKind, String targetId, String contentRevision) { }
    public record ExternalList(List<ExternalView> items) { }
    private static String referenceName(String owner, String key) {
        if (owner == null || owner.isBlank() || "anonymousUser".equals(owner)) { throw problem(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "需要认证来源"); }
        if (key == null || !key.matches("[a-zA-Z0-9._:-]{1,128}")) { throw problem(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE_KEY", "引用键限 128 个字母、数字及 . _ : -"); }
        return "ref-" + hash((owner + "\0" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private java.util.Optional<FontReference> ownReference(String owner, String key) {
        return client.fetch(FontReference.class, referenceName(owner, key)).filter(r -> r.getMetadata().getDeletionTimestamp() == null)
            .map(r -> {
                if (!owner.equals(r.getSpec().getOwner()) || !key.equals(r.getSpec().getKey())) {
                    throw problem(HttpStatus.FORBIDDEN, "REFERENCE_OWNER_MISMATCH", "引用所有权不匹配");
                }
                return r;
            });
    }
    private ExternalView externalView(FontReference r) {
        var s = r.getSpec();
        return new ExternalView(s.getOwner(), s.getKey(), r.getMetadata().getVersion(), s.getTargetKind(), s.getTargetId(), s.getContentRevision());
    }
    public synchronized ExternalList externalReferences(String owner) {
        referenceName(owner, "identity-check");
        return new ExternalList(all(FontReference.class).stream().filter(r -> owner.equals(r.getSpec().getOwner())).map(this::externalView).toList());
    }
    public synchronized ExternalView registerReference(String owner, String key, ExternalUpdate request) {
        String name = referenceName(owner, key);
        String revision = text(request.contentRevision(), 160, true);
        var existing = ownReference(owner, key);
        if (existing.isPresent()) {
            var old = existing.get().getSpec();
            if (Objects.equals(old.getTargetKind(), request.targetKind()) && Objects.equals(old.getTargetId(), request.targetId())
                && revision.equals(old.getContentRevision())) { return externalView(existing.get()); }
            version(existing.get(), request.version());
            if (!Objects.equals(old.getTargetKind(), request.targetKind()) || !Objects.equals(old.getTargetId(), request.targetId())) {
                if (!"CONTENT_UPDATED".equals(request.confirmation()) || !old.getTargetKind().equals(request.previousKind())
                    || !old.getTargetId().equals(request.previousId()) || revision.equals(old.getContentRevision())) {
                    throw problem(HttpStatus.CONFLICT, "EXTERNAL_MIGRATION_CONFIRMATION_REQUIRED", "来源须先迁移实际外部内容，再以新内容版本及旧目标确认；插件不会改写外部文章");
                }
            }
        } else if (request.version() != null || request.previousId() != null || request.confirmation() != null) {
            throw problem(HttpStatus.CONFLICT, "REFERENCE_NOT_FOUND", "引用不存在，不能将迁移确认作为首次登记");
        }
        if ("family".equals(request.targetKind())) { publicFamily(request.targetId()); }
        else if ("scheme".equals(request.targetKind())) { publicScheme(request.targetId()); }
        else { throw problem(HttpStatus.BAD_REQUEST, "INVALID_KIND", "登记目标仅支持公开 family/scheme"); }
        var value = existing.orElseGet(() -> { var r = new FontReference(); var m = new Metadata(); m.setName(name); r.setMetadata(m); return r; });
        var spec = new FontReference.ReferenceSpec(); spec.setOwner(owner); spec.setKey(key);
        spec.setTargetKind(request.targetKind()); spec.setTargetId(request.targetId()); spec.setContentRevision(revision); value.setSpec(spec);
        if (existing.isEmpty()) { client.create(value); } else { client.update(value); }
        return externalView(ownReference(owner, key).orElseThrow());
    }
    public synchronized void unregisterReference(String owner, String key, Long expectedVersion) {
        var value = ownReference(owner, key);
        if (value.isEmpty()) { return; } // Repeated own-source withdrawal is idempotent.
        version(value.get(), expectedVersion); client.delete(value.get());
    }

    public record Replacement(String kind, String oldId, String newId, Long oldVersion, Long newVersion) { }
    public record ReplacementResult(String state, List<Reference> updated, List<Reference> remaining,
        String externalMigration, String failure) { }
    /** Resumable per-record updates, not a cross-extension database transaction. Never delete the old target. */
    public synchronized ReplacementResult replaceInternal(Replacement request) {
        String kind = request.kind();
        if (!List.of("family", "scheme").contains(Objects.toString(kind, "")) || Objects.equals(request.oldId(), request.newId())) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_REPLACEMENT", "替换须为同类型的两个不同 family/scheme");
        }
        if ("family".equals(kind)) {
            version(family(request.oldId()), request.oldVersion()); version(family(request.newId()), request.newVersion()); publicFamily(request.newId());
        } else {
            version(scheme(request.oldId()), request.oldVersion()); version(scheme(request.newId()), request.newVersion()); publicScheme(request.newId());
        }
        var schemesToChange = all(FontScheme.class).stream().filter(s -> "family".equals(kind) && usesFamily(s, request.oldId())).toList();
        var rulesToChange = orderedRules().stream().filter(r -> kind.equals(r.getSpec().getTargetKind()) && request.oldId().equals(r.getSpec().getTargetId())).toList();
        // Preflight every affected record before writing. Disabled records retain references and must still be compatible.
        for (var s : schemesToChange) {
            var v = s.getSpec();
            if (!commonVariants(v.getLatinFamilyId().equals(request.oldId()) ? request.newId() : v.getLatinFamilyId(),
                v.getCjkFamilyId().equals(request.oldId()) ? request.newId() : v.getCjkFamilyId()).contains(new Variant(v.getWeight(), v.getStyle()))) {
                throw problem(HttpStatus.CONFLICT, "REPLACEMENT_VARIANT_UNAVAILABLE", "替换字体不能满足现有方案的真实字重、样式或字符范围");
            }
        }
        for (var r : rulesToChange) {
            var v = r.getSpec(); var probe = new FontRule.RuleSpec();
            probe.setTargetKind(kind); probe.setTargetId(request.newId()); probe.setWeight(v.getWeight());
            if (ruleTarget(probe, new ArrayList<>()).faces().isEmpty()) { throw problem(HttpStatus.CONFLICT, "REPLACEMENT_VARIANT_UNAVAILABLE", "替换目标不能满足现有规则的真实字重"); }
        }
        var updated = new ArrayList<Reference>(); String failure = "";
        try {
            for (var s : schemesToChange) {
                var v = s.getSpec();
                saveScheme(s.getMetadata().getName(), new SchemeUpdate(s.getMetadata().getVersion(), v.getDisplayName(), v.isEnabled(),
                    v.getLatinFamilyId().equals(request.oldId()) ? request.newId() : v.getLatinFamilyId(),
                    v.getCjkFamilyId().equals(request.oldId()) ? request.newId() : v.getCjkFamilyId(),
                    v.getRangePolicy(), v.getWeight(), v.getStyle(), v.getFallbacks()));
                updated.add(new Reference("scheme", s.getMetadata().getName(), v.getDisplayName()));
            }
            for (var r : rulesToChange) {
                var v = r.getSpec();
                saveRule(r.getMetadata().getName(), new RuleUpdate(r.getMetadata().getVersion(), v.getDisplayName(), v.isEnabled(),
                    v.getThemeName(), v.getScope(), v.getContentName(), v.getSelector(), kind, request.newId(), v.getWeight(), v.getOrder(), v.isForce()));
                updated.add(new Reference("rule", r.getMetadata().getName(), v.getDisplayName()));
            }
        } catch (RuntimeException ex) {
            failure = "部分写入未完成；重新读取关系后可重试，旧目标及外部登记保留。";
        }
        var remaining = references(kind, request.oldId()).items().stream().filter(r -> !"face".equals(r.kind())).toList();
        return new ReplacementResult(failure.isBlank() ? (remaining.isEmpty() ? "INTERNAL_COMPLETE" : "EXTERNAL_CONFIRMATION_REQUIRED") : "PARTIAL",
            List.copyOf(updated), remaining, "外部登记未更改；每个来源须先更新自己的内容，再提交带旧目标/版本的新内容版本确认，或明确撤销。此结果不证明外部迁移完成。", failure);
    }

    private Options options() {
        var value = settings.fetch("resources", Options.class).orElse(new Options("khon-font-book-local", null));
        return value; // Legacy parserExecutable is ignored by the built-in parser.
    }

    public FontParser.RuntimeInfo runtime() { return parser.runtime(options().parserExecutable()); }

    private <T extends run.halo.app.extension.Extension> List<T> all(Class<T> type) {
        return client.list(type, e -> e.getMetadata().getDeletionTimestamp() == null,
            java.util.Comparator.comparing(e -> e.getMetadata().getName()));
    }

    public Catalog catalog(int page, int size, String keyword) {
        if (page < 1 || size < 1 || size > 100) { throw problem(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页范围无效"); }
        var families = all(FontFamily.class);
        var assets = all(FontAsset.class);
        var faces = all(FontFace.class).stream().filter(face -> keyword == null || keyword.isBlank()
            || face.getSpec().getMetadata().family().toLowerCase(java.util.Locale.ROOT).contains(keyword.toLowerCase(java.util.Locale.ROOT))
            || families.stream().anyMatch(f -> f.getMetadata().getName().equals(face.getSpec().getFamilyId())
                && f.getSpec().getDisplayName().contains(keyword))).toList();
        int from = (int) Math.min((long) (page - 1) * size, faces.size());
        var visible = faces.subList(from, Math.min(from + size, faces.size())).stream().map(face -> {
            try {
                var asset = asset(face.getSpec().getAssetId());
                var attachment = attachment(asset.getSpec().getAttachmentName());
                safeUri(attachment);
                if (!"READY".equals(asset.getStatus().getState())) { return new FaceView(face, "INCOMPLETE", asset.getStatus().getMessage()); }
                if (!Objects.equals(asset.getSpec().getPermalinkObserved(), attachment.getStatus().getPermalink())) {
                    return new FaceView(face, "URL_CHANGED", "附件地址已变更，请重新校验");
                }
                return new FaceView(face, "READY", "附件记录存在；预览或重新校验时核对实际文件");
            } catch (Exception ex) { return new FaceView(face, "MISSING", "附件缺失、存储配置变化或地址不可读取"); }
        }).toList();
        String storageMessage = "字体存储策略已准备；实际读写在导入与预览时检查";
        String policyName = "";
        List<AttachmentChoice> choices = List.of();
        try {
            var policy = policy();
            choices = all(Attachment.class).stream().filter(a -> policy.getMetadata().getName().equals(a.getSpec().getPolicyName()))
                .filter(a -> List.of("font/woff2", "font/ttf", "application/x-font-ttf", "application/x-font-truetype", "application/octet-stream").contains(Objects.toString(a.getSpec().getMediaType(), "")))
                .map(a -> new AttachmentChoice(a.getMetadata().getName(), a.getSpec().getDisplayName(), a.getSpec().getSize())).toList();
            policyName = policy.getMetadata().getName();
        } catch (org.springframework.web.server.ResponseStatusException ex) { storageMessage = ex.getReason();
        } catch (Exception ex) { storageMessage = "字体存储尚未准备完成，请刷新后重试"; }
        return new Catalog(families, assets, visible, choices, runtime(), policyName, storageMessage, page, size, faces.size());
    }

    private Policy policy() {
        return storage.ensureConfigured();
    }

    public ImportResult importFile(FilePart file, ImportFields fields, String username) {
        return parsingRequest(() -> importFileLocked(file, fields, username));
    }

    private synchronized ImportResult importFileLocked(FilePart file, ImportFields fields, String username) {
        validateFields(fields);
        if (fields.familyId() != null && !fields.familyId().isBlank()) { family(fields.familyId()); }
        policy();
        byte[] bytes;
        try {
            var buffer = DataBufferUtils.join(file.content(), FontParser.MAX_BYTES).block(Duration.ofSeconds(30));
            if (buffer == null) { throw problem(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "请选择 WOFF2 或 TTF 文件"); }
            try { bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); }
            finally { DataBufferUtils.release(buffer); }
        } catch (DataBufferLimitException ex) { throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件不得超过 20 MiB"); }
        var format = FontFormat.detect(bytes);
        if (!file.filename().toLowerCase(java.util.Locale.ROOT).endsWith(format.extension())) {
            throw problem(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FORMAT", "扩展名须为 .woff2 或 .ttf，并与真实文件格式一致");
        }
        var metadata = parser.parse(options().parserExecutable(), bytes);
        var hash = hash(bytes);
        var existing = byHash(hash);
        if (existing != null) { return finish(existing, metadata, fields, true); }
        var part = new SimpleFilePart(UUID.randomUUID() + format.extension(),
            Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))), MediaType.parseMediaType(format.mediaType()));
        var uploaded = attachments.upload(username, policy().getMetadata().getName(), null, part,
            a -> a.getMetadata().setLabels(Map.of("khon-font-book.tungwenblog.dev/managed", "true")))
            .block(Duration.ofSeconds(30));
        if (uploaded == null) { throw problem(HttpStatus.BAD_GATEWAY, "UPLOAD_FAILED", "附件上传未返回结果"); }
        return saveImport(uploaded, bytes, hash, metadata, fields, "plugin-created");
    }

    public ImportResult importExisting(ExistingImport request) {
        return parsingRequest(() -> importExistingLocked(request));
    }

    private synchronized ImportResult importExistingLocked(ExistingImport request) {
        var fields = new ImportFields(request.familyId(), request.source(), request.license(), request.category());
        validateFields(fields);
        if (fields.familyId() != null && !fields.familyId().isBlank()) { family(fields.familyId()); }
        policy();
        var a = attachment(request.attachmentName());
        var bytes = read(a);
        var metadata = parser.parse(options().parserExecutable(), bytes);
        var hash = hash(bytes);
        var existing = byHash(hash);
        if (existing != null) { return finish(existing, metadata, fields, true); }
        return saveImport(a, bytes, hash, metadata, fields, "existing");
    }

    private ImportResult saveImport(Attachment attachment, byte[] bytes, String hash, FontMetadata metadata, ImportFields fields, String ownership) {
        var asset = new FontAsset();
        asset.setMetadata(identity("asset"));
        var spec = new FontAsset.AssetSpec();
        spec.setAttachmentName(attachment.getMetadata().getName()); spec.setSha256(hash); spec.setSize(bytes.length);
        spec.setSource(text(fields.source(), 1000, true)); spec.setLicense(text(fields.license(), 2000, true)); spec.setCategory(text(fields.category(), 80, false));
        spec.setOwnership(ownership); spec.setPermalinkObserved(attachment.getStatus().getPermalink()); asset.setSpec(spec);
        var status = new FontAsset.AssetStatus(); status.setState("IMPORTING"); status.setMessage("等待保存字形"); asset.setStatus(status);
        try { client.create(asset); }
        catch (Exception ex) {
            // Never delete an existing/user attachment. Return its stable ID so the user can retry by selecting it.
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "PARTIAL_IMPORT", "附件已保留，但资源记录未保存。请重新选择附件重试：" + spec.getAttachmentName());
        }
        return finish(asset, metadata, fields, false);
    }

    private ImportResult finish(FontAsset asset, FontMetadata metadata, ImportFields fields, boolean duplicate) {
        var assetId = asset.getMetadata().getName();
        var faces = all(FontFace.class).stream().filter(f -> assetId.equals(f.getSpec().getAssetId())).toList();
        FontFamily createdFamily = null;
        try {
            var currentBytes = read(attachment(asset.getSpec().getAttachmentName()));
            if (!hash(currentBytes).equals(asset.getSpec().getSha256())) {
                throw problem(HttpStatus.CONFLICT, "CONTENT_CHANGED", "同内容资源的附件已改变，请先重新校验或移除旧记录");
            }
            if (!faces.isEmpty() && "READY".equals(asset.getStatus().getState())) {
                return new ImportResult(faces.getFirst(), true, "相同内容已存在，未重复上传或更改归属");
            }
            FontFace face;
            if (!faces.isEmpty()) { face = faces.getFirst(); }
            else {
                FontFamily family;
                if (fields.familyId() == null || fields.familyId().isBlank()) {
                    family = createFamily(new FamilyUpdate(null, metadata.family(), true)); createdFamily = family;
                } else { family = family(fields.familyId()); }
                face = new FontFace(); face.setMetadata(identity("face"));
                var spec = new FontFace.FaceSpec(); spec.setFamilyId(family.getMetadata().getName());
                spec.setAssetId(asset.getMetadata().getName()); spec.setMetadata(metadata); face.setSpec(spec);
                client.create(face);
            }
            asset = asset(asset.getMetadata().getName());
            asset.getStatus().setState("READY"); asset.getStatus().setMessage(""); client.update(asset);
            return new ImportResult(face(face.getMetadata().getName()), duplicate, duplicate ? "已恢复未完成的导入" : "字体已导入");
        } catch (Exception ex) {
            if (createdFamily != null) {
                String familyId = createdFamily.getMetadata().getName();
                if (all(FontFace.class).stream().noneMatch(f -> familyId.equals(f.getSpec().getFamilyId()))) {
                    try { client.delete(family(familyId)); } catch (Exception ignored) { /* Retain for explicit cleanup. */ }
                }
            }
            var current = asset(asset.getMetadata().getName());
            current.getStatus().setState("FAILED"); current.getStatus().setMessage("导入部分失败，附件已保留，可重新选择附件重试");
            try { client.update(current); } catch (Exception ignored) { /* Original failure stays actionable via attachment ID. */ }
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "PARTIAL_IMPORT", "附件已保留，导入未完成，请重新选择附件重试：" + asset.getSpec().getAttachmentName());
        }
    }

    private FontAsset byHash(String hash) {
        return all(FontAsset.class).stream().filter(a -> hash.equals(a.getSpec().getSha256())).findFirst().orElse(null);
    }

    public synchronized FontFamily createFamily(FamilyUpdate request) {
        var family = new FontFamily(); family.setMetadata(identity("family")); family.setSpec(new FontFamily.FamilySpec());
        family.getSpec().setDisplayName(text(request.displayName(), 160, true)); family.getSpec().setEnabled(request.enabled());
        client.create(family); return family(family.getMetadata().getName());
    }

    public synchronized FontFamily updateFamily(String id, FamilyUpdate request) {
        var family = family(id); version(family, request.version());
        family.getSpec().setDisplayName(text(request.displayName(), 160, true)); family.getSpec().setEnabled(request.enabled());
        client.update(family); return family(id);
    }

    public synchronized FontFace updateFace(String id, FaceUpdate request) {
        var face = face(id); version(face, request.version()); family(request.familyId());
        if (!face.getSpec().getFamilyId().equals(request.familyId()) && !references("face", id).items().isEmpty()) {
            throw problem(HttpStatus.CONFLICT, "RESOURCE_REFERENCED", "字形所属字体族被方案或规则引用，请先调整引用再移动字形");
        }
        face.getSpec().setFamilyId(request.familyId()); face.getSpec().setEnabled(request.enabled());
        client.update(face); return face(id);
    }

    public synchronized FontAsset updateAsset(String id, AssetUpdate request) {
        var asset = asset(id); version(asset, request.version());
        asset.getSpec().setSource(text(request.source(), 1000, true));
        asset.getSpec().setLicense(text(request.license(), 2000, true));
        asset.getSpec().setCategory(text(request.category(), 80, false));
        client.update(asset); return asset(id);
    }

    public synchronized References references(String kind, String id) {
        var refs = new ArrayList<Reference>();
        switch (kind) {
            case "family" -> { family(id); all(FontFace.class).stream().filter(f -> id.equals(f.getSpec().getFamilyId()))
                .forEach(f -> refs.add(new Reference("face", f.getMetadata().getName(), f.getSpec().getMetadata().subfamily())));
                schemeReferences(refs, id); ruleReferences(refs, "family", id); }
            case "asset" -> { asset(id); all(FontFace.class).stream().filter(f -> id.equals(f.getSpec().getAssetId()))
                .forEach(f -> refs.add(new Reference("face", f.getMetadata().getName(), f.getSpec().getMetadata().family()))); }
            case "face" -> { var familyId = face(id).getSpec().getFamilyId(); schemeReferences(refs, familyId); ruleReferences(refs, "family", familyId); }
            case "scheme" -> { scheme(id); ruleReferences(refs, "scheme", id); }
            case "rule" -> rule(id);
            default -> throw problem(HttpStatus.BAD_REQUEST, "INVALID_KIND", "未知资源类型");
        }
        String externalKind = "face".equals(kind) ? "family" : kind;
        String externalId = "face".equals(kind) ? face(id).getSpec().getFamilyId() : id;
        all(FontReference.class).stream().filter(r -> externalKind.equals(r.getSpec().getTargetKind())
            && externalId.equals(r.getSpec().getTargetId())).forEach(r -> refs.add(new Reference("external",
                r.getMetadata().getName(), r.getSpec().getOwner() + " / " + r.getSpec().getKey())));
        return new References(refs, "检查插件内部及已登记外部引用；未登记使用、外部内容是否实际迁移不能自动检测");
    }

    public synchronized void delete(String kind, String id, long expectedVersion) {
        if (!references(kind, id).items().isEmpty()) { throw problem(HttpStatus.CONFLICT, "RESOURCE_REFERENCED", "资源仍被内部或已登记外部引用，请先调整关系或由登记来源确认迁移/撤销"); }
        AbstractExtension resource = switch (kind) { case "family" -> family(id); case "asset" -> asset(id); case "scheme" -> scheme(id); case "rule" -> rule(id); default -> face(id); };
        version(resource, expectedVersion); client.delete(resource);
        // Physical Halo attachment is deliberately retained, including plugin-created attachments.
    }

    public FaceView recheck(String id) {
        return parsingRequest(() -> recheckLocked(id));
    }

    private synchronized FaceView recheckLocked(String id) {
        var face = face(id); var asset = asset(face.getSpec().getAssetId());
        var a = attachment(asset.getSpec().getAttachmentName());
        var bytes = read(a);
        if (!hash(bytes).equals(asset.getSpec().getSha256())) { throw problem(HttpStatus.CONFLICT, "CONTENT_CHANGED", "附件内容已改变，请重新导入，不沿用旧字形元数据"); }
        var metadata = parser.parse(options().parserExecutable(), bytes);
        face.getSpec().setMetadata(metadata); client.update(face);
        asset.getSpec().setPermalinkObserved(a.getStatus().getPermalink());
        asset.getStatus().setState("READY"); asset.getStatus().setMessage(""); client.update(asset);
        return new FaceView(face(id), "READY", "实际文件和元数据重新校验通过");
    }

    public byte[] preview(String id) {
        var face = face(id); var asset = asset(face.getSpec().getAssetId());
        if (!"READY".equals(asset.getStatus().getState())) { throw problem(HttpStatus.CONFLICT, "INCOMPLETE_IMPORT", "导入未完成"); }
        var bytes = read(attachment(asset.getSpec().getAttachmentName()));
        if (!hash(bytes).equals(asset.getSpec().getSha256())) { throw problem(HttpStatus.CONFLICT, "CONTENT_CHANGED", "字体附件内容已改变，请重新导入"); }
        return bytes;
    }

    private URI safeUri(Attachment attachment) {
        if (!Objects.equals(policy().getMetadata().getName(), attachment.getSpec().getPolicyName())) { throw problem(HttpStatus.FORBIDDEN, "ATTACHMENT_FORBIDDEN", "仅允许配置的字体存储策略中的附件"); }
        var link = attachment.getStatus() == null ? null : attachment.getStatus().getPermalink();
        if (link == null || !link.startsWith("/upload/") || link.startsWith("//") || link.contains("..")
            || link.contains("%") || link.contains("\\") || link.contains("?") || link.contains("#")) {
            throw problem(HttpStatus.BAD_REQUEST, "UNSAFE_ATTACHMENT_URL", "附件没有可安全读取的同源本地地址");
        }
        var raw = externalUrl.getRaw();
        if (raw == null) { throw problem(HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_URL", "需要显式配置站点 external-url，不能使用请求 Host 读取附件"); }
        var base = URI.create(raw.toExternalForm());
        if (!List.of("http", "https").contains(base.getScheme()) || base.getUserInfo() != null) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_EXTERNAL_URL", "站点 external-url 配置无效");
        }
        try { return base.resolve(link); }
        catch (IllegalArgumentException ex) { throw problem(HttpStatus.BAD_REQUEST, "UNSAFE_ATTACHMENT_URL", "附件地址不是合法 URI"); }
    }

    private byte[] read(Attachment attachment) {
        policy();
        try {
            var bytes = http.get().uri(safeUri(attachment)).exchangeToMono(response -> {
                if (response.statusCode().value() != 200) { return response.releaseBody().then(reactor.core.publisher.Mono.<byte[]>error(
                    problem(HttpStatus.BAD_GATEWAY, "ATTACHMENT_UNREADABLE", "附件无法匿名读取，HTTP " + response.statusCode().value()))); }
                return response.bodyToMono(byte[].class);
            }).block(Duration.ofSeconds(10));
            if (bytes == null) { throw problem(HttpStatus.BAD_GATEWAY, "ATTACHMENT_UNREADABLE", "附件内容为空"); }
            return bytes;
        } catch (DataBufferLimitException ex) { throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "附件超过 20 MiB");
        } catch (org.springframework.web.server.ResponseStatusException ex) { throw ex; }
        catch (Exception ex) { throw problem(HttpStatus.BAD_GATEWAY, "ATTACHMENT_UNREADABLE", "附件读取失败；不跟随重定向、不转发登录凭据"); }
    }

    private static void validateFields(ImportFields fields) {
        text(fields.source(), 1000, true); text(fields.license(), 2000, true); text(fields.category(), 80, false);
    }
    static String text(String value, int max, boolean required) {
        var v = value == null ? "" : value.trim();
        if (required && v.isBlank() || v.length() > max || v.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_FIELD", "字段为空、过长或含不允许的控制字符");
        }
        return v;
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static void version(AbstractExtension resource, Long expected) {
        if (expected == null || !expected.equals(resource.getMetadata().getVersion())) {
            throw problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "数据已变更，请刷新后重试");
        }
    }
    static Metadata identity(String prefix) { var m = new Metadata(); m.setName(prefix + "-" + UUID.randomUUID()); return m; }
    private FontFamily family(String id) { return client.fetch(FontFamily.class, Objects.requireNonNullElse(id, "")).filter(f -> f.getMetadata().getDeletionTimestamp() == null)
        .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "FAMILY_NOT_FOUND", "字体族不存在")); }
    private FontFace face(String id) { return client.fetch(FontFace.class, id).filter(f -> f.getMetadata().getDeletionTimestamp() == null)
        .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "FACE_NOT_FOUND", "字形不存在")); }
    private FontAsset asset(String id) { return client.fetch(FontAsset.class, id).filter(f -> f.getMetadata().getDeletionTimestamp() == null)
        .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "字体资源不存在")); }
    private Attachment attachment(String id) { return client.fetch(Attachment.class, id).filter(f -> f.getMetadata().getDeletionTimestamp() == null)
        .orElseThrow(() -> problem(HttpStatus.NOT_FOUND, "ATTACHMENT_MISSING", "字体附件不存在")); }
}

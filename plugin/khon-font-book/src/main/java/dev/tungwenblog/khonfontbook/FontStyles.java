package dev.tungwenblog.khonfontbook;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.SettingFetcher;

/** Bounded, process-local immutable CSS snapshots. Latest HTML always recompiles authoritative data. */
@Component
@RequiredArgsConstructor
public class FontStyles {
    static final String ROOT = "/apis/api.font-book.tungwenblog.dev/v1alpha1/styles/";
    static final int MAX_ENTRIES = 128;
    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final int MAX_SNAPSHOT_BYTES = 1024 * 1024;
    private final FontService fonts;
    private final SettingFetcher settings;
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>(16, .75f, true);
    private int bytes;
    private boolean running;

    public record Publication(Boolean injectionEnabled) { }
    public record Snapshot(String sha256, String css) {
        public String path() { return ROOT + sha256 + ".css"; }
    }
    public boolean injectionEnabled() {
        try { return settings.fetch("publication", Publication.class).map(p -> Boolean.TRUE.equals(p.injectionEnabled())).orElse(false); }
        catch (RuntimeException ex) { return false; }
    }
    public synchronized void start() { snapshots.clear(); bytes = 0; running = true; }
    public synchronized void stop() { running = false; snapshots.clear(); bytes = 0; }

    public synchronized Optional<Snapshot> latest(FontService.CompileContext context) {
        if (!running || !injectionEnabled()) { return Optional.empty(); }
        var result = fonts.compile(context);
        if (!injectionEnabled() || result.css().isBlank()) { return Optional.empty(); }
        return retain(result);
    }
    /** Explicit integration class styles work independently of the theme-injection switch. */
    public synchronized FontPublic.Style integration(String kind, String id) {
        if (!running) { throw FontParser.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "PLUGIN_STOPPED", "插件未运行"); }
        synchronized (fonts) {
            var rule = fonts.publicStyleRule(kind, id);
            String css = FontCss.compile(java.util.List.of(rule));
            String revision = FontService.hash(css.getBytes(StandardCharsets.UTF_8));
            var snapshot = retain(new FontService.CompiledCss(FontCss.VERSION, revision, css, java.util.List.of(), java.util.List.of(), ""))
                .orElseThrow(() -> FontParser.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "STYLE_TOO_LARGE", "样式超出发布限额"));
            return new FontPublic.Style("v1alpha1", kind, id, revision, snapshot.path(), rule.spec().getSelector().substring(1),
                "--kfb-" + FontCss.token(rule), rule.target().fallbacks().getLast());
        }
    }
    synchronized Optional<Snapshot> retain(FontService.CompiledCss result) {
        var encoded = result.css().getBytes(StandardCharsets.UTF_8);
        if (!running || encoded.length > MAX_SNAPSHOT_BYTES || !FontService.hash(encoded).equals(result.sha256())) { return Optional.empty(); }
        var existing = snapshots.get(result.sha256());
        if (existing != null) {
            if (!existing.css().equals(result.css())) { throw new IllegalStateException("CSS digest collision"); }
            return Optional.of(existing);
        }
        var snapshot = new Snapshot(result.sha256(), result.css());
        snapshots.put(snapshot.sha256(), snapshot); bytes += encoded.length;
        while (snapshots.size() > MAX_ENTRIES || bytes > MAX_BYTES) {
            var oldest = snapshots.pollFirstEntry();
            bytes -= oldest.getValue().css().getBytes(StandardCharsets.UTF_8).length;
        }
        return Optional.of(snapshot);
    }
    public synchronized Optional<Snapshot> find(String file) {
        if (!running || file == null || !file.matches("[a-f0-9]{64}\\.css")) { return Optional.empty(); }
        return Optional.ofNullable(snapshots.get(file.substring(0, 64)));
    }
}

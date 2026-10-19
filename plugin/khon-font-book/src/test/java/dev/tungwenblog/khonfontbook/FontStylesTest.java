package dev.tungwenblog.khonfontbook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import run.halo.app.plugin.SettingFetcher;

class FontStylesTest {
    private final FontService fonts = mock(FontService.class);
    private final SettingFetcher settings = mock(SettingFetcher.class);
    private final FontStyles styles = new FontStyles(fonts, settings);
    private final FontService.CompileContext context = new FontService.CompileContext("theme-a", "Unknown", "");
    static FontService.CompiledCss compiled(String css) {
        return new FontService.CompiledCss(FontCss.VERSION, FontService.hash(css.getBytes(StandardCharsets.UTF_8)), css, List.of(), List.of(), "test");
    }
    @Test void defaultsAndConfigurationFailureAreClosedAndEveryPageUsesFreshCompilation() {
        styles.start();
        when(settings.fetch("publication", FontStyles.Publication.class)).thenReturn(Optional.empty());
        assertTrue(styles.latest(context).isEmpty()); verifyNoInteractions(fonts);
        when(settings.fetch("publication", FontStyles.Publication.class)).thenReturn(Optional.of(new FontStyles.Publication(true)));
        when(fonts.compile(context)).thenReturn(compiled(".a{font-family:serif}"), compiled(".b{font-family:serif}"));
        var first = styles.latest(context).orElseThrow(); var second = styles.latest(context).orElseThrow();
        assertNotEquals(first.sha256(), second.sha256());
        assertEquals(first.css(), styles.find(first.sha256() + ".css").orElseThrow().css());
        when(settings.fetch("publication", FontStyles.Publication.class)).thenThrow(new IllegalArgumentException("Malformed config"));
        assertTrue(styles.latest(context).isEmpty());
        styles.stop(); assertTrue(styles.find(first.sha256() + ".css").isEmpty());
        styles.start(); assertTrue(styles.find(first.sha256() + ".css").isEmpty());
    }
    @Test void snapshotsAreBoundedAndUnknownOrMismatchedHashesNeverCompileNewContent() {
        styles.start();
        var first = styles.retain(compiled("/* first */")).orElseThrow();
        for (int i = 0; i < FontStyles.MAX_ENTRIES; i++) { styles.retain(compiled("/* " + i + " */")); }
        assertTrue(styles.find(first.sha256() + ".css").isEmpty());
        assertTrue(styles.find("../../x.css").isEmpty());
        assertTrue(styles.retain(compiled("x".repeat(FontStyles.MAX_SNAPSHOT_BYTES + 1))).isEmpty());
        assertTrue(styles.retain(new FontService.CompiledCss("test", "0".repeat(64), "x", List.of(), List.of(), "test")).isEmpty());
        verifyNoInteractions(fonts);
    }
    @Test void publishedHttpBytesEtag304UnknownAndStopAreConsistent() {
        styles.start(); var snapshot = styles.retain(compiled(".正文{font-family:serif}")).orElseThrow();
        var client = WebTestClient.bindToRouterFunction(new FontStyleEndpoint(styles).endpoint()).build();
        String path = "/styles/" + snapshot.sha256() + ".css";
        client.get().uri(path).exchange().expectStatus().isOk().expectHeader().valueEquals("ETag", '"' + snapshot.sha256() + '"')
            .expectHeader().valueEquals("Cache-Control", "max-age=31536000, public, immutable")
            .expectHeader().valueEquals("Content-Type", "text/css;charset=UTF-8")
            .expectBody(byte[].class).isEqualTo(snapshot.css().getBytes(StandardCharsets.UTF_8));
        client.get().uri(path).header("If-None-Match", "W/\"" + snapshot.sha256() + "\"").exchange()
            .expectStatus().isNotModified().expectHeader().valueEquals("ETag", '"' + snapshot.sha256() + '"').expectBody().isEmpty();
        client.get().uri("/styles/" + "0".repeat(64) + ".css").exchange().expectStatus().isNotFound().expectHeader().valueEquals("Cache-Control", "no-store");
        styles.stop(); client.get().uri(path).exchange().expectStatus().isNotFound();
    }
}

package dev.tungwenblog.khonfontbook;

import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

class FontEndpointTest {
    @Test void managementCatalogMustNotBeStoredByCaches() {
        var fonts = mock(FontService.class);
        when(fonts.catalog(1, 20, "")).thenReturn(new FontService.Catalog(
            List.of(), List.of(), List.of(), List.of(),
            new FontParser.RuntimeInfo(false, "Not configured", "", ""), "fonts", "", 1, 20, 0));
        WebTestClient.bindToRouterFunction(new FontEndpoint(fonts).endpoint()).build()
            .get().uri("/catalog").exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-store");
    }

    @Test void fontPreviewUsesItsActualFormatAndDisablesSniffing() {
        var fonts = mock(FontService.class);
        byte[] ttf = {0, 1, 0, 0};
        when(fonts.preview("face-test")).thenReturn(ttf);
        WebTestClient.bindToRouterFunction(new FontEndpoint(fonts).endpoint()).build()
            .get().uri("/preview/face-test").exchange().expectStatus().isOk()
            .expectHeader().contentType("font/ttf")
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
            .expectBody(byte[].class).isEqualTo(ttf);
    }
}

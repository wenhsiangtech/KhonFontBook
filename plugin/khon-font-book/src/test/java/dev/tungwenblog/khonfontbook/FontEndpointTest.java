package dev.tungwenblog.khonfontbook;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class FontEndpointTest {
    @ParameterizedTest
    @CsvSource({"POST,/rules", "PUT,/rules/rule-test", "POST,/schemes", "PUT,/schemes/scheme-test",
        "POST,/imports/attachment", "POST,/families", "PUT,/families/family-test",
        "PUT,/faces/face-test", "PUT,/assets/asset-test"})
    void requiredManagementBodyRejectsMissingAndJsonNull(String method, String path) {
        var fonts = mock(FontService.class);
        var http = WebTestClient.bindToRouterFunction(new FontEndpoint(fonts).endpoint()).build();
        http.method(HttpMethod.valueOf(method)).uri(path).contentType(MediaType.APPLICATION_JSON)
            .exchange().expectStatus().isBadRequest();
        http.method(HttpMethod.valueOf(method)).uri(path).contentType(MediaType.APPLICATION_JSON)
            .bodyValue("null").exchange().expectStatus().isBadRequest();
        verifyNoInteractions(fonts);
    }

    @Test void activeRecheckStillReportsParserShutdownAsUnavailable() {
        var fonts = mock(FontService.class);
        when(fonts.recheck("face-test")).thenThrow(FontParser.problem(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "PARSER_CANCELLED", "内置解析器已停止"));
        WebTestClient.bindToRouterFunction(new FontEndpoint(fonts).endpoint()).build()
            .post().uri("/faces/face-test/recheck").exchange().expectStatus().isEqualTo(503);
    }
    @Test void cancelledRecheckCleansUpWithoutReportingAnUnhandledError() throws Exception {
        var fonts = mock(FontService.class);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var exited = new java.util.concurrent.CountDownLatch(1);
        var dropped = new java.util.concurrent.CountDownLatch(1);
        reactor.core.publisher.Hooks.onErrorDropped(error -> dropped.countDown());
        when(fonts.recheck("face-test")).thenAnswer(invocation -> {
            entered.countDown();
            try { new java.util.concurrent.CountDownLatch(1).await(); return null; }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw FontParser.problem(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "PARSER_CANCELLED", "字体解析已中断");
            } finally { exited.countDown(); }
        });
        var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
            org.springframework.mock.http.server.reactive.MockServerHttpRequest.post("/faces/face-test/recheck").build());
        var request = org.springframework.web.reactive.function.server.ServerRequest.create(exchange,
            org.springframework.web.reactive.function.server.HandlerStrategies.withDefaults().messageReaders());
        var handler = new FontEndpoint(fonts).endpoint().route(request).block();
        var subscription = handler.handle(request).subscribe();
        try {
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
            subscription.dispose();
            assertTrue(exited.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(dropped.await(300, java.util.concurrent.TimeUnit.MILLISECONDS), "Expected cancellation was reported as an unhandled error");
        } finally { subscription.dispose(); reactor.core.publisher.Hooks.resetOnErrorDropped(); }
    }
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

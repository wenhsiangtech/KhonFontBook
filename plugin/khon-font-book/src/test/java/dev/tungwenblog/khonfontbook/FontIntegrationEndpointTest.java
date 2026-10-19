package dev.tungwenblog.khonfontbook;

import static org.mockito.Mockito.*;

import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class FontIntegrationEndpointTest {
    @Test void principalIsRequiredAndEmptyMutationsAreRejectedBeforeCallingService() {
        var fonts = mock(FontService.class);
        var route = new FontIntegrationEndpoint(fonts).endpoint();
        WebTestClient.bindToRouterFunction(route).build().get().uri("/external-references")
            .exchange().expectStatus().isUnauthorized();
        when(fonts.externalReferences("source-a")).thenReturn(new FontService.ExternalList(List.of()));
        var client = WebTestClient.bindToRouterFunction(route)
            .webFilter((exchange, chain) -> chain.filter(exchange.mutate()
                .principal(Mono.just((Principal) () -> "source-a")).build())).build();
        client.get().uri("/external-references").exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-store");
        client.put().uri("/external-references/post:one").contentType(MediaType.APPLICATION_JSON)
            .exchange().expectStatus().isBadRequest();
        client.post().uri("/replacements").contentType(MediaType.APPLICATION_JSON)
            .exchange().expectStatus().isBadRequest();
        verify(fonts).externalReferences("source-a"); verifyNoMoreInteractions(fonts);
    }
}

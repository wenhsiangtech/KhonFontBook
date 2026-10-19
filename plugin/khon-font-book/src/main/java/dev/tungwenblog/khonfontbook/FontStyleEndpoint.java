package dev.tungwenblog.khonfontbook;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
@RequiredArgsConstructor
public class FontStyleEndpoint implements CustomEndpoint {
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();
    private static final MediaType CSS = new MediaType("text", "css", StandardCharsets.UTF_8);
    private final FontStyles styles;

    @Override public GroupVersion groupVersion() { return GroupVersion.parseAPIVersion("api.font-book.tungwenblog.dev/v1alpha1"); }
    @Override public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route().GET("/styles/{file}", this::read, b -> b.operationId("GetPublishedFontStyle").tag("KhonFontBookV1alpha1Public")
            .parameter(parameterBuilder().name("file").in(ParameterIn.PATH).required(true).implementation(String.class))
            .response(responseBuilder().content(contentBuilder().mediaType("text/css").schema(schemaBuilder().type("string")))))
            .build();
    }
    private Mono<ServerResponse> read(ServerRequest request) {
        return Mono.defer(() -> styles.find(request.pathVariable("file")).map(snapshot ->
            request.checkNotModified('"' + snapshot.sha256() + '"')
                .flatMap(response -> ServerResponse.from(response).cacheControl(CACHE).header("X-Content-Type-Options", "nosniff").build())
                .switchIfEmpty(ServerResponse.ok().contentType(CSS).eTag(snapshot.sha256()).cacheControl(CACHE)
                    .header("X-Content-Type-Options", "nosniff").bodyValue(snapshot.css().getBytes(StandardCharsets.UTF_8))))
            .orElseGet(() -> ServerResponse.notFound().cacheControl(CacheControl.noStore()).build()));
    }
}

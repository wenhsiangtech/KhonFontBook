package dev.tungwenblog.khonfontbook;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.security.Principal;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
@RequiredArgsConstructor
public class FontIntegrationEndpoint implements CustomEndpoint {
    private static final String TAG = "KhonFontBookV1alpha1Integration";
    private final FontService fonts;
    @Override public GroupVersion groupVersion() { return GroupVersion.parseAPIVersion("console.api.font-book.tungwenblog.dev/v1alpha1"); }
    @Override public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/external-references", r -> owner(r).flatMap(o -> json(() -> fonts.externalReferences(o))),
                b -> b.operationId("ListOwnFontReferences").tag(TAG).response(responseBuilder().implementation(FontService.ExternalList.class)))
            .PUT("/external-references/{key}", r -> owner(r).flatMap(o -> r.bodyToMono(FontService.ExternalUpdate.class)
                .switchIfEmpty(Mono.error(FontParser.problem(HttpStatus.BAD_REQUEST, "BODY_REQUIRED", "需要登记内容")))
                .flatMap(body -> json(() -> fonts.registerReference(o, r.pathVariable("key"), body)))),
                b -> b.operationId("RegisterOwnFontReference").tag(TAG).parameter(key())
                    .requestBody(requestBodyBuilder().required(true).implementation(FontService.ExternalUpdate.class))
                    .response(responseBuilder().implementation(FontService.ExternalView.class)))
            .DELETE("/external-references/{key}", r -> owner(r).flatMap(o -> Mono.fromRunnable(() -> {
                Long version;
                try { version = Long.valueOf(r.queryParam("version").orElse("")); }
                catch (NumberFormatException ex) { throw FontParser.problem(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "撤销需要登记版本"); }
                fonts.unregisterReference(o, r.pathVariable("key"), version);
            }).subscribeOn(Schedulers.boundedElastic()).then(ServerResponse.noContent().build())),
                b -> b.operationId("WithdrawOwnFontReference").tag(TAG).parameter(key())
                    .parameter(parameterBuilder().name("version").in(ParameterIn.QUERY).required(true).implementation(Long.class))
                    .response(responseBuilder().responseCode("204").description("Own-source claim absent; external content is not modified")))
            .POST("/replacements", r -> r.bodyToMono(FontService.Replacement.class)
                .switchIfEmpty(Mono.error(FontParser.problem(HttpStatus.BAD_REQUEST, "BODY_REQUIRED", "需要替换内容")))
                .flatMap(body -> json(() -> fonts.replaceInternal(body))),
                b -> b.operationId("ReplaceInternalFontTarget").tag(TAG)
                    .requestBody(requestBodyBuilder().required(true).implementation(FontService.Replacement.class))
                    .response(responseBuilder().implementation(FontService.ReplacementResult.class)))
            .build();
    }
    private static Mono<String> owner(ServerRequest r) {
        return r.principal().map(Principal::getName).filter(n -> !n.isBlank() && !"anonymousUser".equals(n))
            .switchIfEmpty(Mono.error(FontParser.problem(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "需要认证来源")));
    }
    private static org.springdoc.core.fn.builders.parameter.Builder key() {
        return parameterBuilder().name("key").in(ParameterIn.PATH).required(true).implementation(String.class);
    }
    private static Mono<ServerResponse> json(Callable<?> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic()).flatMap(v -> ServerResponse.ok()
            .cacheControl(CacheControl.noStore()).bodyValue(v));
    }
}

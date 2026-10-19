package dev.tungwenblog.khonfontbook;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
public class FontPublicEndpoint implements CustomEndpoint {
    private static final String TAG = "KhonFontBookV1alpha1Public";
    private final FontService fonts;
    private final FontStyles styles;
    @Override public GroupVersion groupVersion() { return GroupVersion.parseAPIVersion("api.font-book.tungwenblog.dev/v1alpha1"); }
    @Override public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/capabilities", r -> json(() -> new FontPublic.Capabilities("v1alpha1", styles.injectionEnabled(),
                "data-khon-font-book", "data-khon-font-book-integration",
                "主题规则由 Head 独立加载；集成方仅为显式 kfb-* class 加载目标样式，按 href 去重，不代替 Head、不编译任意页面。",
                "只保护内部和已登记外部引用；撤销与迁移为来源声明，不自动验证外部文章。")),
                b -> b.operationId("GetFontCapabilities").tag(TAG).response(responseBuilder().implementation(FontPublic.Capabilities.class)))
            .GET("/families", r -> json(() -> fonts.publicFamilies(page(r, "page", 1), page(r, "size", 20))),
                b -> b.operationId("ListPublicFontFamilies").tag(TAG).parameter(query("page")).parameter(query("size"))
                    .response(responseBuilder().implementation(FontPublic.Families.class)))
            .GET("/families/{id}", r -> json(() -> fonts.publicFamily(r.pathVariable("id"))),
                b -> b.operationId("GetPublicFontFamily").tag(TAG).parameter(id()).response(responseBuilder().implementation(FontPublic.Family.class)))
            .GET("/families/{id}/style", r -> json(() -> styles.integration("family", r.pathVariable("id"))),
                b -> b.operationId("GetPublicFontFamilyStyle").tag(TAG).parameter(id()).response(responseBuilder().implementation(FontPublic.Style.class)))
            .GET("/schemes", r -> json(() -> fonts.publicSchemes(page(r, "page", 1), page(r, "size", 20))),
                b -> b.operationId("ListPublicFontSchemes").tag(TAG).parameter(query("page")).parameter(query("size"))
                    .response(responseBuilder().implementation(FontPublic.Schemes.class)))
            .GET("/schemes/{id}", r -> json(() -> fonts.publicScheme(r.pathVariable("id"))),
                b -> b.operationId("GetPublicFontScheme").tag(TAG).parameter(id()).response(responseBuilder().implementation(FontPublic.Scheme.class)))
            .GET("/schemes/{id}/style", r -> json(() -> styles.integration("scheme", r.pathVariable("id"))),
                b -> b.operationId("GetPublicFontSchemeStyle").tag(TAG).parameter(id()).response(responseBuilder().implementation(FontPublic.Style.class)))
            .build();
    }
    private static org.springdoc.core.fn.builders.parameter.Builder id() {
        return parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class);
    }
    private static org.springdoc.core.fn.builders.parameter.Builder query(String name) {
        return parameterBuilder().name(name).in(ParameterIn.QUERY).implementation(Integer.class);
    }
    private static int page(ServerRequest r, String name, int fallback) {
        try { return Integer.parseInt(r.queryParam(name).orElse(Integer.toString(fallback))); }
        catch (NumberFormatException ex) { throw FontParser.problem(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页参数无效"); }
    }
    private static Mono<ServerResponse> json(Callable<?> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic()).flatMap(v -> ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).bodyValue(v));
    }
}

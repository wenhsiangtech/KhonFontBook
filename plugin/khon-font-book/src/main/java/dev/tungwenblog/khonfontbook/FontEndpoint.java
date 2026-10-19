package dev.tungwenblog.khonfontbook;

import static org.springdoc.core.fn.builders.apiresponse.Builder.responseBuilder;
import static org.springdoc.core.fn.builders.content.Builder.contentBuilder;
import static org.springdoc.core.fn.builders.parameter.Builder.parameterBuilder;
import static org.springdoc.core.fn.builders.requestbody.Builder.requestBodyBuilder;
import static org.springdoc.core.fn.builders.schema.Builder.schemaBuilder;

import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.springdoc.webflux.core.fn.SpringdocRouteBuilder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
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
public class FontEndpoint implements CustomEndpoint {
    private static final String TAG = "KhonFontBookV1alpha1Console";
    private final FontService service;

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("console.api.font-book.tungwenblog.dev/v1alpha1");
    }

    @Schema(types = "object")
    public interface UploadForm {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) FilePart getFile();
        String getFamilyId();
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String getSource();
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String getLicense();
        String getCategory();
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return SpringdocRouteBuilder.route()
            .GET("/rules", r -> json(() -> service.rules(integer(r, "page", 1), integer(r, "size", 20), r.queryParam("keyword").orElse(""))),
                b -> b.operationId("ListFontRules").tag(TAG)
                    .parameter(parameterBuilder().name("page").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("size").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("keyword").in(ParameterIn.QUERY).implementation(String.class))
                    .response(responseBuilder().implementation(FontService.RuleCatalog.class)))
            .POST("/rules", r -> r.bodyToMono(FontService.RuleUpdate.class).flatMap(body -> json(() -> service.saveRule(null, body))),
                b -> b.operationId("CreateFontRule").tag(TAG)
                    .requestBody(requestBodyBuilder().required(true).implementation(FontService.RuleUpdate.class))
                    .response(responseBuilder().implementation(FontRule.class)))
            .PUT("/rules/{id}", r -> r.bodyToMono(FontService.RuleUpdate.class).flatMap(body -> json(() -> service.saveRule(r.pathVariable("id"), body))),
                b -> b.operationId("UpdateFontRule").tag(TAG).parameter(idParameter())
                    .requestBody(requestBodyBuilder().required(true).implementation(FontService.RuleUpdate.class))
                    .response(responseBuilder().implementation(FontRule.class)))
            .DELETE("/rules/{id}", r -> delete(r, "rule"), b -> b.operationId("DeleteFontRule").tag(TAG)
                .parameter(idParameter()).parameter(versionParameter())
                .response(responseBuilder().responseCode("204").description("Deleted; targets retained")))
            .GET("/rule-options", r -> json(() -> service.ruleOptions(r.queryParam("contentKind").orElse("Post"),
                integer(r, "page", 1), integer(r, "size", 20), r.queryParam("keyword").orElse(""))),
                b -> b.operationId("GetFontRuleOptions").tag(TAG)
                    .parameter(parameterBuilder().name("contentKind").in(ParameterIn.QUERY).implementation(String.class))
                    .parameter(parameterBuilder().name("page").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("size").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("keyword").in(ParameterIn.QUERY).implementation(String.class))
                    .response(responseBuilder().implementation(FontService.RuleOptions.class)))
            .GET("/rule-compilation", r -> json(() -> service.compile(new FontService.CompileContext(r.queryParam("themeName").orElse(""),
                r.queryParam("contentKind").orElse("Unknown"), r.queryParam("contentName").orElse("")))),
                b -> b.operationId("CompileFontRules").tag(TAG)
                    .parameter(parameterBuilder().name("themeName").in(ParameterIn.QUERY).required(true).implementation(String.class))
                    .parameter(parameterBuilder().name("contentKind").in(ParameterIn.QUERY).implementation(String.class))
                    .parameter(parameterBuilder().name("contentName").in(ParameterIn.QUERY).implementation(String.class))
                    .response(responseBuilder().implementation(FontService.CompiledCss.class)))
            .GET("/schemes", r -> json(() -> service.schemes(integer(r, "page", 1), integer(r, "size", 20), r.queryParam("keyword").orElse(""))),
                b -> b.operationId("ListFontSchemes").tag(TAG)
                    .parameter(parameterBuilder().name("page").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("size").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("keyword").in(ParameterIn.QUERY).implementation(String.class))
                    .response(responseBuilder().implementation(FontService.SchemeCatalog.class)))
            .POST("/schemes", r -> r.bodyToMono(FontService.SchemeUpdate.class).flatMap(body -> json(() -> service.saveScheme(null, body))),
                b -> b.operationId("CreateFontScheme").tag(TAG)
                    .requestBody(requestBodyBuilder().required(true).implementation(FontService.SchemeUpdate.class))
                    .response(responseBuilder().implementation(FontScheme.class)))
            .PUT("/schemes/{id}", r -> r.bodyToMono(FontService.SchemeUpdate.class).flatMap(body -> json(() -> service.saveScheme(r.pathVariable("id"), body))),
                b -> b.operationId("UpdateFontScheme").tag(TAG).parameter(idParameter())
                    .requestBody(requestBodyBuilder().required(true).implementation(FontService.SchemeUpdate.class))
                    .response(responseBuilder().implementation(FontScheme.class)))
            .DELETE("/schemes/{id}", r -> delete(r, "scheme"), b -> b.operationId("DeleteFontScheme").tag(TAG)
                .parameter(idParameter()).parameter(versionParameter())
                .response(responseBuilder().responseCode("204").description("Deleted")))
            .GET("/schemes/{id}/preview", r -> json(() -> service.schemePreview(r.pathVariable("id"))),
                b -> b.operationId("GetFontSchemePreview").tag(TAG).parameter(idParameter())
                    .response(responseBuilder().implementation(FontService.SchemePreview.class)))
            .GET("/scheme-preview-fonts/{id}", r -> Mono.fromCallable(() -> service.schemePreviewFont(
                    r.queryParam("schemeId").orElse(""), r.pathVariable("id"), r.queryParam("revision").orElse("")))
                    .subscribeOn(Schedulers.boundedElastic()).flatMap(bytes -> ServerResponse.ok()
                        .contentType(MediaType.parseMediaType(FontFormat.detect(bytes).mediaType())).cacheControl(CacheControl.noStore())
                        .header("X-Content-Type-Options", "nosniff").bodyValue(bytes)),
                b -> b.operationId("GetFontSchemePreviewFont").tag(TAG).parameter(idParameter())
                    .parameter(parameterBuilder().name("schemeId").in(ParameterIn.QUERY).required(true).implementation(String.class))
                    .parameter(parameterBuilder().name("revision").in(ParameterIn.QUERY).required(true).implementation(String.class))
                    .response(responseBuilder().content(contentBuilder().mediaType("font/ttf")
                        .schema(schemaBuilder().type("string").format("binary"))).content(contentBuilder().mediaType("font/woff2")
                        .schema(schemaBuilder().type("string").format("binary")))))
            .GET("/catalog", r -> json(() -> service.catalog(integer(r, "page", 1), integer(r, "size", 20), r.queryParam("keyword").orElse(""))),
                b -> b.operationId("GetFontCatalog").tag(TAG)
                    .parameter(parameterBuilder().name("page").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("size").in(ParameterIn.QUERY).implementation(Integer.class))
                    .parameter(parameterBuilder().name("keyword").in(ParameterIn.QUERY).implementation(String.class))
                    .response(responseBuilder().implementation(FontService.Catalog.class)))
            .POST("/imports", this::upload, b -> b.operationId("ImportFontFile").tag(TAG)
                .requestBody(requestBodyBuilder().required(true).content(contentBuilder().mediaType("multipart/form-data")
                    .schema(schemaBuilder().implementation(UploadForm.class))))
                .response(responseBuilder().implementation(FontService.ImportResult.class)))
            .POST("/imports/attachment", r -> r.bodyToMono(FontService.ExistingImport.class)
                .flatMap(body -> json(() -> service.importExisting(body))), b -> b.operationId("ImportFontAttachment").tag(TAG)
                .requestBody(requestBodyBuilder().required(true).implementation(FontService.ExistingImport.class))
                .response(responseBuilder().implementation(FontService.ImportResult.class)))
            .POST("/families", r -> r.bodyToMono(FontService.FamilyUpdate.class).flatMap(body -> json(() -> service.createFamily(body))),
                b -> b.operationId("CreateFontFamily").tag(TAG)
                    .requestBody(requestBodyBuilder().required(true).implementation(FontService.FamilyUpdate.class))
                    .response(responseBuilder().implementation(FontFamily.class)))
            .PUT("/families/{id}", r -> r.bodyToMono(FontService.FamilyUpdate.class).flatMap(body -> json(() -> service.updateFamily(r.pathVariable("id"), body))),
                b -> b.operationId("UpdateFontFamily").tag(TAG).parameter(idParameter())
                    .requestBody(requestBodyBuilder().required(true).implementation(FontService.FamilyUpdate.class))
                    .response(responseBuilder().implementation(FontFamily.class)))
            .PUT("/faces/{id}", r -> r.bodyToMono(FontService.FaceUpdate.class).flatMap(body -> json(() -> service.updateFace(r.pathVariable("id"), body))),
                b -> b.operationId("UpdateFontFace").tag(TAG).parameter(idParameter())
                    .requestBody(requestBodyBuilder().required(true).implementation(FontService.FaceUpdate.class))
                    .response(responseBuilder().implementation(FontFace.class)))
            .PUT("/assets/{id}", r -> r.bodyToMono(FontService.AssetUpdate.class).flatMap(body -> json(() -> service.updateAsset(r.pathVariable("id"), body))),
                b -> b.operationId("UpdateFontAsset").tag(TAG).parameter(idParameter())
                    .requestBody(requestBodyBuilder().required(true).implementation(FontService.AssetUpdate.class))
                    .response(responseBuilder().implementation(FontAsset.class)))
            .DELETE("/families/{id}", r -> delete(r, "family"), b -> b.operationId("DeleteFontFamily").tag(TAG).parameter(idParameter()).parameter(versionParameter())
                .response(responseBuilder().responseCode("204").description("Deleted; Halo attachment retained")))
            .DELETE("/faces/{id}", r -> delete(r, "face"), b -> b.operationId("DeleteFontFace").tag(TAG).parameter(idParameter()).parameter(versionParameter())
                .response(responseBuilder().responseCode("204").description("Deleted; Halo attachment retained")))
            .DELETE("/assets/{id}", r -> delete(r, "asset"), b -> b.operationId("DeleteFontAsset").tag(TAG).parameter(idParameter()).parameter(versionParameter())
                .response(responseBuilder().responseCode("204").description("Deleted; Halo attachment retained")))
            .GET("/references", r -> json(() -> service.references(r.queryParam("kind").orElse(""), r.queryParam("id").orElse(""))),
                b -> b.operationId("GetFontReferences").tag(TAG)
                    .parameter(parameterBuilder().name("kind").in(ParameterIn.QUERY).required(true).implementation(String.class))
                    .parameter(parameterBuilder().name("id").in(ParameterIn.QUERY).required(true).implementation(String.class))
                    .response(responseBuilder().implementation(FontService.References.class)))
            .POST("/faces/{id}/recheck", r -> json(() -> service.recheck(r.pathVariable("id"))),
                b -> b.operationId("RecheckFontFace").tag(TAG).parameter(idParameter())
                    .response(responseBuilder().implementation(FontService.FaceView.class)))
            .GET("/preview/{id}", r -> Mono.fromCallable(() -> service.preview(r.pathVariable("id"))).subscribeOn(Schedulers.boundedElastic())
                    .flatMap(bytes -> ServerResponse.ok().contentType(MediaType.parseMediaType(FontFormat.detect(bytes).mediaType()))
                        .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").bodyValue(bytes)),
                b -> b.operationId("GetFontPreview").tag(TAG).parameter(idParameter())
                    .response(responseBuilder().content(contentBuilder().mediaType("font/ttf")
                        .schema(schemaBuilder().type("string").format("binary"))).content(contentBuilder().mediaType("font/woff2")
                        .schema(schemaBuilder().type("string").format("binary")))))
            .build();
    }

    private Mono<ServerResponse> upload(ServerRequest request) {
        long length = request.headers().contentLength().orElse(-1);
        if (length < 0) {
            return Mono.error(FontParser.problem(HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED", "上传需要 Content-Length，暂不支持分块上传"));
        }
        if (length > FontParser.MAX_BYTES + 65536L) {
            return Mono.error(FontParser.problem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "上传请求超过 20 MiB 与表单开销限制"));
        }
        return request.principal().switchIfEmpty(Mono.error(FontParser.problem(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "需要登录")))
            .flatMap(principal -> request.multipartData().flatMap(parts -> {
                if (!(parts.getFirst("file") instanceof FilePart file)) {
                    return Mono.error(FontParser.problem(HttpStatus.BAD_REQUEST, "FILE_REQUIRED", "请选择 WOFF2 或 TTF 文件"));
                }
                return json(() -> service.importFile(file, new FontService.ImportFields(
                    field(parts.getFirst("familyId")), field(parts.getFirst("source")),
                    field(parts.getFirst("license")), field(parts.getFirst("category"))), principal.getName()));
            }));
    }
    private static String field(Object part) { return part instanceof FormFieldPart value ? value.value() : ""; }
    private static int integer(ServerRequest request, String name, int fallback) {
        try { return Integer.parseInt(request.queryParam(name).orElse(Integer.toString(fallback))); }
        catch (NumberFormatException ex) { throw FontParser.problem(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "分页参数无效"); }
    }
    private Mono<ServerResponse> delete(ServerRequest request, String kind) {
        return Mono.fromRunnable(() -> {
            long version;
            try { version = Long.parseLong(request.queryParam("version").orElse("")); }
            catch (NumberFormatException ex) { throw FontParser.problem(HttpStatus.BAD_REQUEST, "VERSION_REQUIRED", "需要资源版本"); }
            service.delete(kind, request.pathVariable("id"), version);
        }).subscribeOn(Schedulers.boundedElastic()).then(ServerResponse.noContent().build());
    }
    private static org.springdoc.core.fn.builders.parameter.Builder idParameter() {
        return parameterBuilder().name("id").in(ParameterIn.PATH).required(true).implementation(String.class);
    }
    private static org.springdoc.core.fn.builders.parameter.Builder versionParameter() {
        return parameterBuilder().name("version").in(ParameterIn.QUERY).required(true).implementation(Long.class);
    }
    private static Mono<ServerResponse> json(Callable<?> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic()).flatMap(value -> ServerResponse.ok()
            .cacheControl(CacheControl.noStore()).bodyValue(value));
    }
}

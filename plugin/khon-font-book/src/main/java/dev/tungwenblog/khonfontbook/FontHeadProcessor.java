package dev.tungwenblog.khonfontbook;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.model.AttributeValueQuotes;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import org.thymeleaf.spring6.web.webflux.ISpringWebFluxWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.theme.dialect.TemplateHeadProcessor;
import run.halo.app.theme.router.ModelConst;

@Slf4j
@Component
@RequiredArgsConstructor
public class FontHeadProcessor implements TemplateHeadProcessor {
    static final String MARKER = "data-khon-font-book";
    private final FontStyles styles;

    @Override public Mono<Void> process(ITemplateContext context, IModel model, IElementModelStructureHandler handler) {
        return Mono.fromRunnable(() -> {
            if (!(context instanceof IWebContext web) || !(web.getExchange() instanceof ISpringWebFluxWebExchange exchange)
                || !(exchange.getNativeExchangeObject() instanceof ServerWebExchange nativeExchange)
                || !nativeExchange.getAttributes().containsKey(ModelConst.POWERED_BY_HALO_TEMPLATE_ENGINE)) { return; }
            // Mark even when switched off, so a rendered no-link page cannot become stale after re-enabling.
            nativeExchange.getAttributes().put(ModelConst.NO_CACHE, true);
            if (!nativeExchange.getResponse().isCommitted()) { nativeExchange.getResponse().getHeaders().setCacheControl("no-store"); }
            for (int i = 0; i < model.size(); i++) {
                if (model.get(i) instanceof IProcessableElementTag tag && tag.hasAttribute(MARKER)) { return; }
            }
            var identity = identity(context);
            if (identity == null) { return; }
            styles.latest(identity).ifPresent(snapshot -> model.add(context.getModelFactory().createStandaloneElementTag("link",
                Map.of("rel", "stylesheet", "href", snapshot.path(), MARKER, snapshot.sha256()), AttributeValueQuotes.DOUBLE, false, true)));
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(ex -> {
            log.warn("KhonFontBook Head skipped: context/configuration or compilation unavailable ({})", ex.getClass().getSimpleName());
            return Mono.empty();
        }).then();
    }

    static FontService.CompileContext identity(ITemplateContext context) {
        var theme = context.getVariable("theme");
        if (theme == null) { return null; }
        // Halo 2.27 ThemeVo lives in application, not the plugin API. Read its verified bean property.
        var bean = new BeanWrapperImpl(theme);
        if (!bean.isReadableProperty("metadata.name") || !(bean.getPropertyValue("metadata.name") instanceof String themeName) || themeName.isBlank()) { return null; }
        var gvk = context.getVariable("groupVersionKind");
        String kind = Post.GVK.equals(gvk) ? "Post" : SinglePage.GVK.equals(gvk) ? "SinglePage" : "Unknown";
        if ("Unknown".equals(kind) && context instanceof IWebContext web
            && web.getExchange() instanceof ISpringWebFluxWebExchange exchange
            && exchange.getNativeExchangeObject() instanceof ServerWebExchange request
            && "/".equals(request.getRequest().getPath().pathWithinApplication().value())) {
            kind = "Home";
        }
        String name = "";
        if (!"Unknown".equals(kind) && !"Home".equals(kind)) {
            if (!(context.getVariable("name") instanceof String id) || id.isBlank()) { return null; }
            name = id;
        }
        return new FontService.CompileContext(themeName, kind, name);
    }
}

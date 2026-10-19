package dev.tungwenblog.khonfontbook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import lombok.Getter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.model.IModel;
import org.thymeleaf.model.IModelFactory;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.model.IStandaloneElementTag;
import org.thymeleaf.processor.element.IElementModelStructureHandler;
import org.thymeleaf.spring6.web.webflux.ISpringWebFluxWebExchange;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.Metadata;
import run.halo.app.theme.router.ModelConst;

class FontHeadProcessorTest {
    @Getter public static class ThemeBean {
        private final Metadata metadata = new Metadata();
        public ThemeBean() { metadata.setName("theme-preview"); }
    }
    private ITemplateContext context() {
        var context = mock(ITemplateContext.class, withSettings().extraInterfaces(IWebContext.class));
        when(context.getVariable("theme")).thenReturn(new ThemeBean()); return context;
    }
    @Test void homepageUsesActualRootRequestNotContentOrQueryHints() {
        var context = context();
        var web = mock(ISpringWebFluxWebExchange.class);
        when(((IWebContext) context).getExchange()).thenReturn(web);
        when(web.getNativeExchangeObject()).thenReturn(MockServerWebExchange.from(MockServerHttpRequest.get("/?preview-theme=other")));
        assertEquals("Home", FontHeadProcessor.identity(context).contentKind());
        for (var path : new String[] {"/page/2", "/archives", "/anything?home=true"}) {
            when(web.getNativeExchangeObject()).thenReturn(MockServerWebExchange.from(MockServerHttpRequest.get(path)));
            assertEquals("Unknown", FontHeadProcessor.identity(context).contentKind());
        }
        when(context.getVariable("groupVersionKind")).thenReturn(Post.GVK);
        when(context.getVariable("name")).thenReturn("post-id");
        when(web.getNativeExchangeObject()).thenReturn(MockServerWebExchange.from(MockServerHttpRequest.get("/")));
        assertEquals("Post", FontHeadProcessor.identity(context).contentKind());
    }
    @Test void identityUsesResolvedThemeAndExactGvkAndStableName() {
        var context = context(); when(context.getVariable("name")).thenReturn("stable-id");
        when(context.getVariable("groupVersionKind")).thenReturn(Post.GVK);
        assertEquals(new FontService.CompileContext("theme-preview", "Post", "stable-id"), FontHeadProcessor.identity(context));
        when(context.getVariable("groupVersionKind")).thenReturn(SinglePage.GVK);
        assertEquals("SinglePage", FontHeadProcessor.identity(context).contentKind());
        when(context.getVariable("name")).thenReturn(null); assertNull(FontHeadProcessor.identity(context));
        when(context.getVariable("groupVersionKind")).thenReturn("Post");
        assertEquals(new FontService.CompileContext("theme-preview", "Unknown", ""), FontHeadProcessor.identity(context));
        when(context.getVariable("theme")).thenReturn(null); assertNull(FontHeadProcessor.identity(context));
    }
    @Test void headInjectsOneLinkMarksHtmlUncacheableAndFailsClosed() {
        var styles = mock(FontStyles.class); var head = new FontHeadProcessor(styles); var context = context();
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/?preview-theme=theme-untrusted"));
        exchange.getAttributes().put(ModelConst.POWERED_BY_HALO_TEMPLATE_ENGINE, false); // Authorized preview may disable caching.
        var web = mock(ISpringWebFluxWebExchange.class);
        when(web.getNativeExchangeObject()).thenReturn(exchange); when(((IWebContext) context).getExchange()).thenReturn(web);
        var model = mock(IModel.class); var factory = mock(IModelFactory.class); var tag = mock(IStandaloneElementTag.class);
        when(context.getModelFactory()).thenReturn(factory);
        when(factory.createStandaloneElementTag(eq("link"), anyMap(), any(), eq(false), eq(true))).thenReturn(tag);
        when(styles.latest(any())).thenReturn(Optional.of(new FontStyles.Snapshot("a".repeat(64), "css")));
        var handler = mock(IElementModelStructureHandler.class);
        head.process(context, model, handler).block(); verify(model).add(tag);
        assertEquals("no-store", exchange.getResponse().getHeaders().getCacheControl());
        assertEquals(true, exchange.getAttribute(ModelConst.NO_CACHE));
        var existing = mock(IProcessableElementTag.class); when(existing.hasAttribute(FontHeadProcessor.MARKER)).thenReturn(true);
        when(model.size()).thenReturn(1); when(model.get(0)).thenReturn(existing); clearInvocations(styles);
        head.process(context, model, handler).block(); verifyNoInteractions(styles);
        when(model.size()).thenReturn(0); when(styles.latest(any())).thenThrow(new IllegalStateException("Unavailable"));
        assertDoesNotThrow(() -> head.process(context, model, handler).block());
        exchange.getAttributes().clear(); clearInvocations(styles);
        head.process(context, model, handler).block(); verifyNoInteractions(styles);
    }
}

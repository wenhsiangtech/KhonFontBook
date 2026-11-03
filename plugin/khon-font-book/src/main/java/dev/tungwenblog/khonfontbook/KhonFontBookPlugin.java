package dev.tungwenblog.khonfontbook;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;

/**
 * <p>Plugin main class to manage the lifecycle of the plugin.</p>
 * <p>This class must be public and have a public constructor.</p>
 * <p>Only one main class extending {@link BasePlugin} is allowed per plugin.</p>
 *
 * @author KhonChi
 * @since 0.1.0
 */
@Component
public class KhonFontBookPlugin extends BasePlugin {
    private final SchemeManager schemes;
    private final FontService fonts;
    private final FontStyles styles;
    private final FontParser parser;

    public KhonFontBookPlugin(PluginContext pluginContext, SchemeManager schemes, FontService fonts, FontStyles styles, FontParser parser) {
        super(pluginContext);
        this.schemes = schemes;
        this.fonts = fonts;
        this.styles = styles;
        this.parser = parser;
    }

    @Override
    public void start() {
        parser.start();
        schemes.register(FontFamily.class);
        schemes.register(FontAsset.class);
        schemes.register(FontFace.class);
        schemes.register(FontScheme.class);
        schemes.register(FontRule.class);
        schemes.register(FontReference.class);
        fonts.runtime();
        styles.start();
    }

    @Override
    public void stop() {
        styles.stop();
        parser.stop();
        schemes.unregister(Scheme.buildFromType(FontReference.class));
        schemes.unregister(Scheme.buildFromType(FontRule.class));
        schemes.unregister(Scheme.buildFromType(FontScheme.class));
        schemes.unregister(Scheme.buildFromType(FontFace.class));
        schemes.unregister(Scheme.buildFromType(FontAsset.class));
        schemes.unregister(Scheme.buildFromType(FontFamily.class));
    }

}

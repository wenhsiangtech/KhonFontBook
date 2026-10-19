package dev.tungwenblog.khonfontbook;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "font-book.tungwenblog.dev", version = "v1alpha1", kind = "FontScheme",
    plural = "fontschemes", singular = "fontscheme")
public class FontScheme extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private SchemeSpec spec;

    @Data
    public static class SchemeSpec {
        private String displayName;
        private boolean enabled;
        private String latinFamilyId;
        private String cjkFamilyId;
        private String rangePolicy;
        private int weight;
        private String style;
        private List<String> fallbacks;
    }
}

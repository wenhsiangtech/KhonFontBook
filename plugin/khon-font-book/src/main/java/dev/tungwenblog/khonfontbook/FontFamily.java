package dev.tungwenblog.khonfontbook;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "font-book.tungwenblog.dev", version = "v1alpha1", kind = "FontFamily",
    plural = "fontfamilies", singular = "fontfamily")
public class FontFamily extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private FamilySpec spec;

    @Data
    public static class FamilySpec {
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 160)
        private String displayName;
        private boolean enabled = true;
    }
}

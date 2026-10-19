package dev.tungwenblog.khonfontbook;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "font-book.tungwenblog.dev", version = "v1alpha1", kind = "FontFace",
    plural = "fontfaces", singular = "fontface")
public class FontFace extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private FaceSpec spec;

    @Data
    public static class FaceSpec {
        private String familyId;
        private String assetId;
        private boolean enabled = true;
        private FontMetadata metadata;
    }
}

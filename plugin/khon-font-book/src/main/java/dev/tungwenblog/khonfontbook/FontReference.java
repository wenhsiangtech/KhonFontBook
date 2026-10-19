package dev.tungwenblog.khonfontbook;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/** Authenticated external claims, never a record of verified external document contents. */
@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "font-book.tungwenblog.dev", version = "v1alpha1", kind = "FontReference",
    plural = "fontreferences", singular = "fontreference")
public class FontReference extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private ReferenceSpec spec;

    @Data
    public static class ReferenceSpec {
        private String owner;
        private String key;
        private String targetKind;
        private String targetId;
        private String contentRevision;
    }
}

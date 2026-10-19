package dev.tungwenblog.khonfontbook;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "font-book.tungwenblog.dev", version = "v1alpha1", kind = "FontAsset",
    plural = "fontassets", singular = "fontasset")
public class FontAsset extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private AssetSpec spec;
    private AssetStatus status;

    @Data
    public static class AssetSpec {
        private String attachmentName;
        private String sha256;
        private long size;
        private String source;
        private String license;
        private String category;
        private String ownership;
        private String permalinkObserved;
    }

    @Data
    public static class AssetStatus {
        private String state;
        private String message;
    }
}

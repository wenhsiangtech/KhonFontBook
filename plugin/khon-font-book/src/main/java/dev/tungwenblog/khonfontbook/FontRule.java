package dev.tungwenblog.khonfontbook;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@Data
@EqualsAndHashCode(callSuper = true)
@GVK(group = "font-book.tungwenblog.dev", version = "v1alpha1", kind = "FontRule",
    plural = "fontrules", singular = "fontrule")
public class FontRule extends AbstractExtension {
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private RuleSpec spec;

    public enum Scope { THEME_ALL, HOME, POST_ALL, POST_ONE, PAGE_ONE }

    @Data
    public static class RuleSpec {
        private String displayName;
        private boolean enabled;
        private String themeName;
        private Scope scope;
        private String contentName;
        private String selector;
        private String targetKind;
        private String targetId;
        private Integer weight;
        private int order;
        private boolean force;
    }
}

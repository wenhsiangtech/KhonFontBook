package dev.tungwenblog.khonfontbook;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class FontRoleTest {
    @Test void existingAttachmentImportIsGrantedToManagersAsAnExactNonResourceAction() throws Exception {
        // Halo 2.27 treats POST /resource/name without a subresource as a
        // non-resource request. An imports/create resource grant is insufficient.
        String path = "/apis/console.api.font-book.tungwenblog.dev/v1alpha1/imports/attachment";
        var granted = new ArrayList<String>();
        try (var input = getClass().getResourceAsStream("/extensions/roles.yaml")) {
            assertNotNull(input);
            for (Object document : new Yaml().loadAll(input)) {
                var role = (Map<?, ?>) document;
                for (Object value : (List<?>) role.get("rules")) {
                    var rule = (Map<?, ?>) value;
                    if (rule.get("nonResourceURLs") instanceof List<?> urls && urls.contains(path)) {
                        assertEquals(List.of(path), urls, "The grant must stay limited to this operation");
                        assertEquals(List.of("create"), rule.get("verbs"));
                        granted.add((String) ((Map<?, ?>) role.get("metadata")).get("name"));
                    }
                }
            }
        }
        assertEquals(List.of("role-template-khon-font-book-manage"), granted);
    }
}

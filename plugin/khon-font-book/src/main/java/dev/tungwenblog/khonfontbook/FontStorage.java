package dev.tungwenblog.khonfontbook;

import static dev.tungwenblog.khonfontbook.FontParser.problem;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.attachment.PolicyTemplate;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import tools.jackson.databind.json.JsonMapper;

/** Owns only dedicated storage preparation; never deletes policies or attachments. */
@Component
public final class FontStorage {
    static final String SETTINGS = "khon-font-book-config";
    static final String DEFAULT_POLICY = "khon-font-book-local";
    static final String OWNER = "khon-font-book.tungwenblog.dev/managed-by";
    static final String VERSION = "khon-font-book.tungwenblog.dev/storage-version";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ExtensionClient client;
    private record LocalPolicy(String location, String maxFileSize, java.util.List<String> allowedFileTypes) { }

    public FontStorage(ExtensionClient client) { this.client = client; }

    public synchronized Policy ensureConfigured() {
        try {
            for (int attempt = 0; attempt < 4; attempt++) {
                var config = client.fetch(ConfigMap.class, SETTINGS).orElseThrow(() -> initializing("插件配置尚未初始化，请刷新后重试"));
                if (config.getMetadata().getDeletionTimestamp() != null)
                    throw initializing("插件字体配置尚未就绪，请刷新后重试");
                var resources = resources(config);
                Object value = resources.get("policyName");
                if (value != null && !(value instanceof String)) throw invalid("字体存储策略标识必须为文字");
                String requested = value == null ? "" : ((String) value).trim();
                String name = requested.isEmpty() ? DEFAULT_POLICY : requested;
                if ("default-policy".equals(name)) throw invalid("不能将全站默认策略用于字体专用存储");
                if (client.fetch(PolicyTemplate.class, "local").isEmpty()) throw initializing("Halo 本地存储模板尚未就绪，请刷新后重试");
                var existing = client.fetch(Policy.class, name);
                if (existing.isPresent()) {
                    try {
                        Policy validated = validate(existing.get());
                        if (persistChoice(requested, name)) return validated;
                        continue;
                    }
                    catch (ResponseStatusException ex) {
                        if (ex.getStatusCode().value() != 400 || owned(existing.get()) || !DEFAULT_POLICY.equals(name)) throw ex;
                    }
                } else if (!DEFAULT_POLICY.equals(name)) {
                    throw invalid("已配置的字体策略不存在，请恢复原策略或检查配置");
                }
                Policy prepared = prepare();
                if (persistChoice(requested, prepared.getMetadata().getName())) return prepared;
            }
            throw initializing("字体配置同时被修改，请刷新后重试");
        } catch (ResponseStatusException ex) { throw ex;
        } catch (RuntimeException ex) {
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "STORAGE_PREPARATION_FAILED", "自动准备字体存储未完成，已创建的专用资源会保留供重试");
        }
    }

    private Policy prepare() {
        boolean hasAssets = !client.list(FontAsset.class, e -> e.getMetadata().getDeletionTimestamp() == null,
            java.util.Comparator.comparing(e -> e.getMetadata().getName())).isEmpty();
        for (int i = 0; i < 16; i++) {
            String name = i == 0 ? DEFAULT_POLICY : DEFAULT_POLICY + "-auto" + (i == 1 ? "" : "-" + (i - 1));
            String configName = name + "-config";
            var existing = client.fetch(Policy.class, name);
            if (existing.isPresent()) {
                if (!owned(existing.get())) continue;
                if (!configName.equals(existing.get().getSpec().getConfigMapName())) throw invalid("专用策略的配置关联已改变，请检查存储配置");
                var paired = client.fetch(ConfigMap.class, configName).orElseThrow(() -> invalid("专用策略的配置缺失，请恢复后重试"));
                if (!owned(paired)) throw invalid("专用策略配置的归属不一致");
                return validate(existing.get());
            }
            // Missing/broken storage with existing font data is not permission to
            // silently switch those resources to a different physical location.
            if (hasAssets) continue;
            ConfigMap settings = client.fetch(ConfigMap.class, configName).orElse(null);
            if (settings != null && !owned(settings)) continue;
            if (settings == null) {
                settings = new ConfigMap(); settings.setMetadata(metadata(configName));
                settings.setData(Map.of("default", JSON.writeValueAsString(new LocalPolicy(
                    i == 0 ? "khon-font-book" : "khon-font-book/" + name, "20MB", java.util.List.of()))));
                settings = createOrRead(ConfigMap.class, settings);
                if (!owned(settings)) continue;
            }
            validateConfig(settings);
            var policy = new Policy(); policy.setMetadata(metadata(name)); policy.setSpec(new Policy.PolicySpec());
            policy.getSpec().setTemplateName("local"); policy.getSpec().setConfigMapName(configName);
            policy.getSpec().setDisplayName("KhonFontBook 字体存储");
            policy = createOrRead(Policy.class, policy);
            if (!owned(policy) || !configName.equals(policy.getSpec().getConfigMapName()))
                throw initializing("字体策略名称在创建时发生冲突，已有资源未被覆盖，请重试");
            return validate(policy);
        }
        throw invalid(hasAssets ? "已有字体记录的原存储不可用，请恢复原策略，不会自动迁移字体"
            : "没有可用的专用存储名称，冲突资源未被修改");
    }

    private <T extends Extension> T createOrRead(Class<T> type, T value) {
        try { client.create(value); }
        catch (RuntimeException ex) {
            // Only accept a raced/partially committed creation when its actual
            // resource can be read and its ownership is checked by the caller.
            return client.fetch(type, value.getMetadata().getName()).orElseThrow(() -> ex);
        }
        return client.fetch(type, value.getMetadata().getName()).orElseThrow(() -> initializing("存储资源创建后尚不可读取，请重试"));
    }

    private boolean persistChoice(String expected, String selected) {
        var current = client.fetch(ConfigMap.class, SETTINGS).orElseThrow(() -> initializing("插件配置暂不可读取"));
        var values = resources(current);
        String actual = Objects.toString(values.get("policyName"), "").trim();
        if (!actual.equals(expected)) return false;
        if (selected.equals(actual)) return true;
        var copy = JSON.convertValue(current, ConfigMap.class);
        var data = copy.getData() == null ? new HashMap<String, String>() : new HashMap<>(copy.getData());
        values.put("policyName", selected);
        data.put("resources", JSON.writeValueAsString(values)); copy.setData(data);
        try { client.update(copy); return true; }
        catch (OptimisticLockingFailureException ex) { return false; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resources(ConfigMap config) {
        if (config.getData() == null || !config.getData().containsKey("resources")) return new HashMap<>();
        try { return new HashMap<>(JSON.readValue(config.getData().get("resources"), Map.class)); }
        catch (RuntimeException ex) { throw invalid("插件字体配置不可读取，请检查配置后重试"); }
    }

    private Policy validate(Policy policy) {
        if (policy.getMetadata().getDeletionTimestamp() != null) throw initializing("字体策略正在删除，不能继续使用");
        if (policy.getSpec() == null || !"local".equals(policy.getSpec().getTemplateName())) throw invalid("字体策略必须使用本地存储模板");
        var config = client.fetch(ConfigMap.class, policy.getSpec().getConfigMapName())
            .orElseThrow(() -> invalid("字体策略的配置不存在"));
        validateConfig(config);
        return policy;
    }

    private static void validateConfig(ConfigMap config) {
        if (config.getMetadata().getDeletionTimestamp() != null) throw initializing("字体存储配置正在删除");
        try {
            var value = JSON.readValue(config.getData().get("default"), LocalPolicy.class);
            String location = Objects.toString(value.location(), "");
            if (location.isBlank() || location.contains("..") || value.allowedFileTypes() != null && !value.allowedFileTypes().isEmpty())
                throw invalid("字体本地策略需要独立子目录和空文件类别列表");
            long limit = DataSize.parse(Objects.toString(value.maxFileSize(), "0")).toBytes();
            if (limit < 1 || limit > FontParser.MAX_BYTES) throw invalid("字体策略大小限制必须为1字节至20 MiB");
        } catch (ResponseStatusException ex) { throw ex;
        } catch (RuntimeException ex) { throw invalid("字体本地策略配置不可读取"); }
    }

    private static boolean owned(Extension resource) {
        var labels = resource.getMetadata().getLabels();
        return labels != null && "khon-font-book".equals(labels.get(OWNER)) && "1".equals(labels.get(VERSION));
    }
    private static Metadata metadata(String name) {
        var metadata = new Metadata(); metadata.setName(name);
        metadata.setLabels(Map.of(OWNER, "khon-font-book", VERSION, "1"));
        return metadata;
    }
    private static ResponseStatusException invalid(String reason) { return problem(HttpStatus.BAD_REQUEST, "FONT_STORAGE_INVALID", reason); }
    private static ResponseStatusException initializing(String reason) { return problem(HttpStatus.SERVICE_UNAVAILABLE, "FONT_STORAGE_INITIALIZING", reason); }
}

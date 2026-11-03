package dev.tungwenblog.khonfontbook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.server.ResponseStatusException;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.attachment.PolicyTemplate;
import run.halo.app.extension.*;
import tools.jackson.databind.json.JsonMapper;

class FontStorageTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ExtensionClient client = mock(ExtensionClient.class);
    private final Map<Class<?>, Map<String, Extension>> store = new HashMap<>();
    private final AtomicBoolean failPolicy = new AtomicBoolean();
    private final AtomicBoolean failSettings = new AtomicBoolean();
    private final AtomicBoolean conflictSettings = new AtomicBoolean();
    private FontStorage storage;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach void setup() {
        when(client.fetch(any(Class.class), anyString())).thenAnswer(i -> {
            synchronized (store) {
                var value = table(i.getArgument(0)).get(i.getArgument(1));
                return Optional.ofNullable(value == null ? null : JSON.convertValue(value, (Class) i.getArgument(0)));
            }
        });
        when(client.list(any(Class.class), any(), any())).thenAnswer(i -> {
            synchronized (store) {
                java.util.function.Predicate<Extension> predicate = i.getArgument(1);
                Comparator<Extension> comparator = i.getArgument(2);
                return table(i.getArgument(0)).values().stream().filter(predicate).sorted(comparator).toList();
            }
        });
        doAnswer(i -> {
            Extension value = i.getArgument(0);
            synchronized (store) {
                if (value instanceof Policy && failPolicy.getAndSet(false)) throw new IllegalStateException("Creation interrupted");
                if (table(value.getClass()).containsKey(value.getMetadata().getName()))
                    throw new org.springframework.dao.DuplicateKeyException("Already exists");
                value.getMetadata().setVersion(0L); seed(value); return null;
            }
        }).when(client).create(any(Extension.class));
        doAnswer(i -> {
            Extension value = i.getArgument(0);
            synchronized (store) {
                if (FontStorage.SETTINGS.equals(value.getMetadata().getName())) {
                    if (failSettings.getAndSet(false)) throw new IllegalStateException("Settings write interrupted");
                    if (conflictSettings.getAndSet(false)) {
                        ConfigMap current = get(ConfigMap.class, FontStorage.SETTINGS);
                        current.getData().put("unrelated", "preserve concurrent edit");
                        current.getMetadata().setVersion(current.getMetadata().getVersion() + 1);
                        throw new OptimisticLockingFailureException("Concurrent update");
                    }
                }
                var current = table(value.getClass()).get(value.getMetadata().getName());
                if (!Objects.equals(value.getMetadata().getVersion(), current.getMetadata().getVersion()))
                    throw new OptimisticLockingFailureException("Stale version");
                value.getMetadata().setVersion(value.getMetadata().getVersion() + 1); seed(value); return null;
            }
        }).when(client).update(any(Extension.class));
        config(FontStorage.SETTINGS, Map.of("resources", "{\"policyName\":\"khon-font-book-local\",\"parserExecutable\":\"/legacy\"}",
            "publication", "{\"injectionEnabled\":false}"));
        var template = new PolicyTemplate(); template.setMetadata(meta("local")); seed(template);
        storage = new FontStorage(client);
    }

    @Test void newInstallCreatesOneOwnedPairAndRestartReusesIt() {
        var first = storage.ensureConfigured();
        var second = new FontStorage(client).ensureConfigured();
        assertEquals(first.getMetadata().getName(), second.getMetadata().getName());
        assertEquals(1, table(Policy.class).size());
        assertEquals(2, table(ConfigMap.class).size());
        assertEquals("khon-font-book", first.getMetadata().getLabels().get(FontStorage.OWNER));
        var policyConfig = get(ConfigMap.class, first.getSpec().getConfigMapName());
        Map<?, ?> data = JSON.readValue(policyConfig.getData().get("default"), Map.class);
        assertEquals("khon-font-book", data.get("location"));
        assertEquals("20MB", data.get("maxFileSize"));
        assertEquals(List.of(), data.get("allowedFileTypes"));
    }

    @Test void newSettingsWithoutLegacyResourcesGroupPrepareStorageAndPreservePublication() {
        config(FontStorage.SETTINGS, Map.of("publication", "{\"injectionEnabled\":false}"));
        assertEquals(FontStorage.DEFAULT_POLICY, storage.ensureConfigured().getMetadata().getName());
        assertEquals(FontStorage.DEFAULT_POLICY, choice());
        assertEquals("{\"injectionEnabled\":false}", get(ConfigMap.class, FontStorage.SETTINGS).getData().get("publication"));
        assertFalse(get(ConfigMap.class, FontStorage.SETTINGS).getData().get("resources").contains("parserExecutable"));
        assertEquals(1, table(Policy.class).size());
    }

    @Test void initializedConfigMapWithNoDataCanPrepareStorage() {
        get(ConfigMap.class, FontStorage.SETTINGS).setData(null);
        assertEquals(FontStorage.DEFAULT_POLICY, storage.ensureConfigured().getMetadata().getName());
        assertEquals(FontStorage.DEFAULT_POLICY, choice());
    }

    @Test void validManualPolicyAndItsFilesConfigurationRemainUnchanged() {
        choose("manual-fonts"); policy("manual-fonts", "local", "manual-config");
        config("manual-config", Map.of("default", local("original-folder")));
        String before = JSON.writeValueAsString(get(Policy.class, "manual-fonts"));
        assertEquals("manual-fonts", storage.ensureConfigured().getMetadata().getName());
        assertEquals(before, JSON.writeValueAsString(get(Policy.class, "manual-fonts")));
        assertEquals(local("original-folder"), get(ConfigMap.class, "manual-config").getData().get("default"));
        assertEquals(1, table(Policy.class).size());
    }

    @Test void incompatibleNameCollisionUsesAndPersistsAnIndependentName() {
        policy(FontStorage.DEFAULT_POLICY, "other-template", "someone-elses-config");
        String before = JSON.writeValueAsString(get(Policy.class, FontStorage.DEFAULT_POLICY));
        var chosen = storage.ensureConfigured();
        assertEquals("khon-font-book-local-auto", chosen.getMetadata().getName());
        assertEquals(before, JSON.writeValueAsString(get(Policy.class, FontStorage.DEFAULT_POLICY)));
        assertEquals(chosen.getMetadata().getName(), choice());
        assertEquals(chosen.getMetadata().getName(), new FontStorage(client).ensureConfigured().getMetadata().getName());
        assertEquals(2, table(Policy.class).size());
        var settings = get(ConfigMap.class, FontStorage.SETTINGS);
        assertEquals("{\"injectionEnabled\":false}", settings.getData().get("publication"));
        assertTrue(settings.getData().get("resources").contains("/legacy"));
    }

    @Test void unownedConfigCollisionIsNotOverwritten() {
        config("khon-font-book-local-config", Map.of("userData", "keep me"));
        assertEquals("khon-font-book-local-auto", storage.ensureConfigured().getMetadata().getName());
        assertEquals(Map.of("userData", "keep me"), get(ConfigMap.class, "khon-font-book-local-config").getData());
    }

    @Test void interruptedPairCreationConvergesToTheSameResourcesOnRetry() {
        failPolicy.set(true);
        assertEquals(503, assertThrows(ResponseStatusException.class, storage::ensureConfigured).getStatusCode().value());
        assertEquals(0, table(Policy.class).size());
        assertEquals(2, table(ConfigMap.class).size());
        assertEquals(FontStorage.DEFAULT_POLICY, storage.ensureConfigured().getMetadata().getName());
        assertEquals(1, table(Policy.class).size());
        assertEquals(2, table(ConfigMap.class).size());
    }

    @Test void failedChoiceWriteReusesThePreparedPairOnRetry() {
        policy(FontStorage.DEFAULT_POLICY, "other-template", "occupied"); failSettings.set(true);
        assertEquals(503, assertThrows(ResponseStatusException.class, storage::ensureConfigured).getStatusCode().value());
        assertEquals("khon-font-book-local-auto", storage.ensureConfigured().getMetadata().getName());
        assertEquals(2, table(Policy.class).size());
        assertEquals(2, table(ConfigMap.class).size());
    }

    @Test void optimisticConflictPreservesConcurrentSettingsAndConverges() {
        policy(FontStorage.DEFAULT_POLICY, "other-template", "occupied"); conflictSettings.set(true);
        assertEquals("khon-font-book-local-auto", storage.ensureConfigured().getMetadata().getName());
        assertEquals("preserve concurrent edit", get(ConfigMap.class, FontStorage.SETTINGS).getData().get("unrelated"));
        assertEquals(2, table(Policy.class).size());
    }

    @Test void independentPreparersDoNotCreateDuplicateResources() throws Exception {
        policy(FontStorage.DEFAULT_POLICY, "other-template", "occupied");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(new FontStorage(client)::ensureConfigured);
            var second = pool.submit(new FontStorage(client)::ensureConfigured);
            assertEquals(first.get(5, TimeUnit.SECONDS).getMetadata().getName(), second.get(5, TimeUnit.SECONDS).getMetadata().getName());
        }
        assertEquals(2, table(Policy.class).size());
        assertEquals(2, table(ConfigMap.class).size());
    }

    @Test void missingInitializationDoesNotCreatePoliciesPrematurely() {
        table(ConfigMap.class).remove(FontStorage.SETTINGS);
        assertEquals(503, assertThrows(ResponseStatusException.class, storage::ensureConfigured).getStatusCode().value());
        assertEquals(0, table(Policy.class).size());
        assertEquals(0, table(ConfigMap.class).size());
    }

    @Test void missingLocalTemplateCanBeRetriedAfterHaloInitialization() {
        var template = get(PolicyTemplate.class, "local"); table(PolicyTemplate.class).clear();
        assertEquals(503, assertThrows(ResponseStatusException.class, storage::ensureConfigured).getStatusCode().value());
        assertEquals(0, table(Policy.class).size());
        seed(template);
        assertEquals(FontStorage.DEFAULT_POLICY, storage.ensureConfigured().getMetadata().getName());
    }

    @Test void brokenStorageWithExistingFontDataDoesNotSwitchPhysicalLocation() {
        var asset = new FontAsset(); asset.setMetadata(meta("existing-font")); seed(asset);
        assertEquals(400, assertThrows(ResponseStatusException.class, storage::ensureConfigured).getStatusCode().value());
        assertEquals(0, table(Policy.class).size());
        assertEquals(1, table(ConfigMap.class).size());
    }

    @Test void invalidExplicitConfigurationIsNotSilentlyReplaced() {
        choose("missing-user-policy");
        assertEquals(400, assertThrows(ResponseStatusException.class, storage::ensureConfigured).getStatusCode().value());
        assertEquals("missing-user-policy", choice());
        assertEquals(0, table(Policy.class).size());
        choose("default-policy");
        assertEquals(400, assertThrows(ResponseStatusException.class, storage::ensureConfigured).getStatusCode().value());
        assertEquals(0, table(Policy.class).size());
    }

    private Map<String, Extension> table(Class<?> type) { return store.computeIfAbsent(type, key -> new HashMap<>()); }
    private void seed(Extension resource) { table(resource.getClass()).put(resource.getMetadata().getName(), JSON.convertValue(resource, resource.getClass())); }
    private <T> T get(Class<T> type, String name) { return type.cast(table(type).get(name)); }
    private static Metadata meta(String name) { var meta = new Metadata(); meta.setName(name); meta.setVersion(0L); return meta; }
    private void config(String name, Map<String, String> data) { var c = new ConfigMap(); c.setMetadata(meta(name)); c.setData(new HashMap<>(data)); seed(c); }
    private void policy(String name, String template, String config) {
        var p = new Policy(); p.setMetadata(meta(name)); p.setSpec(new Policy.PolicySpec()); p.getSpec().setTemplateName(template); p.getSpec().setConfigMapName(config); seed(p);
    }
    private void choose(String name) { get(ConfigMap.class, FontStorage.SETTINGS).getData().put("resources", JSON.writeValueAsString(Map.of("policyName", name))); }
    private String choice() { return JSON.readValue(get(ConfigMap.class, FontStorage.SETTINGS).getData().get("resources"), Map.class).get("policyName").toString(); }
    private static String local(String location) { return "{\"location\":\"" + location + "\",\"maxFileSize\":\"20MB\",\"allowedFileTypes\":[]}"; }
}

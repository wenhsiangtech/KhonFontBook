package dev.tungwenblog.khonfontbook;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.attachment.endpoint.SimpleFilePart;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.*;
import run.halo.app.plugin.SettingFetcher;

class FontServiceTest {
    private final ExtensionClient client = mock(ExtensionClient.class);
    private final AttachmentService uploads = mock(AttachmentService.class);
    private final FontParser parser = mock(FontParser.class);
    private final SettingFetcher settings = mock(SettingFetcher.class);
    private final Map<Class<?>, Map<String, Extension>> store = new HashMap<>();
    private final AtomicBoolean failFace = new AtomicBoolean();
    private final byte[] bytes = new byte[] { 'w', 'O', 'F', '2' };
    private FontService service;
    private HttpServer server;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/upload/font.woff2", exchange -> {
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        when(client.fetch(any(Class.class), anyString())).thenAnswer(i ->
            Optional.ofNullable(store.computeIfAbsent(i.getArgument(0), k -> new HashMap<>()).get(i.getArgument(1))));
        when(client.list(any(Class.class), any(), any())).thenAnswer(i -> {
            java.util.function.Predicate<Extension> predicate = i.getArgument(1);
            Comparator<Extension> comparator = i.getArgument(2);
            return store.computeIfAbsent(i.getArgument(0), k -> new HashMap<>()).values().stream().filter(predicate).sorted(comparator).toList();
        });
        doAnswer(i -> {
            Extension e = i.getArgument(0);
            if (e instanceof FontFace && failFace.get()) { throw new IllegalStateException("Simulated persistence failure"); }
            e.getMetadata().setVersion(0L);
            store.computeIfAbsent(e.getClass(), k -> new HashMap<>()).put(e.getMetadata().getName(), e);
            return null;
        }).when(client).create(any(Extension.class));
        doAnswer(i -> { Extension e = i.getArgument(0); e.getMetadata().setVersion(e.getMetadata().getVersion() + 1); return null; })
            .when(client).update(any(Extension.class));
        doAnswer(i -> { Extension e = i.getArgument(0); store.get(e.getClass()).remove(e.getMetadata().getName()); return null; })
            .when(client).delete(any(Extension.class));
        when(settings.fetch("resources", FontService.Options.class)).thenReturn(Optional.of(new FontService.Options("font-policy", "/python")));
        var policy = new Policy(); policy.setMetadata(meta("font-policy")); policy.setSpec(new Policy.PolicySpec());
        policy.getSpec().setTemplateName("local"); policy.getSpec().setConfigMapName("font-config"); seed(policy);
        var cm = new ConfigMap(); cm.setMetadata(meta("font-config"));
        cm.setData(Map.of("default", "{\"location\":\"fonts\",\"allowedFileTypes\":[],\"maxFileSize\":\"20MB\"}")); seed(cm);
        var pluginConfig = new ConfigMap(); pluginConfig.setMetadata(meta("khon-font-book-config"));
        pluginConfig.setData(Map.of("resources", "{\"policyName\":\"font-policy\"}")); seed(pluginConfig);
        var localTemplate = new run.halo.app.core.extension.attachment.PolicyTemplate(); localTemplate.setMetadata(meta("local")); seed(localTemplate);
        var a = new Attachment(); a.setMetadata(meta("attachment")); a.setSpec(new Attachment.AttachmentSpec());
        a.getSpec().setPolicyName("font-policy"); a.getSpec().setMediaType("font/woff2"); a.getSpec().setSize(4L);
        a.setStatus(new Attachment.AttachmentStatus()); a.getStatus().setPermalink("/upload/font.woff2"); seed(a);
        when(uploads.upload(anyString(), anyString(), isNull(), any(org.springframework.http.codec.multipart.FilePart.class), any())).thenReturn(Mono.just(a));
        when(parser.parse(anyString(), any())).thenReturn(new FontMetadata("Test", "Regular", 400, "normal", 1, 2, 0, "4.67.0", List.of(new FontMetadata.CoverageRange(65, 65))));
        var external = mock(run.halo.app.infra.ExternalUrlSupplier.class);
        when(external.getRaw()).thenReturn(java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort()).toURL());
        service = new FontService(client, uploads, parser, settings, new MockEnvironment(), external, new FontStorage(client));
    }
    @AfterEach void cleanup() { if (server != null) server.stop(0); }
    private static Metadata meta(String name) { var m = new Metadata(); m.setName(name); m.setVersion(0L); return m; }
    private void seed(Extension e) { store.computeIfAbsent(e.getClass(), k -> new HashMap<>()).put(e.getMetadata().getName(), e); }
    private FontService.ImportFields fields() { return new FontService.ImportFields("", "Original source", "Test permission", "Test"); }
    private FontService.ImportResult upload() {
        return service.importFile(new SimpleFilePart("test.woff2", Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))), MediaType.parseMediaType("font/woff2")), fields(), "tester");
    }

    @Test void newInstallAutomaticallyPreparesDedicatedStorageWithoutChangingOtherSettings() {
        store.get(Policy.class).clear();
        var config = new ConfigMap(); config.setMetadata(meta("khon-font-book-config"));
        config.setData(new HashMap<>(Map.of("resources", "{\"policyName\":\"khon-font-book-local\",\"parserExecutable\":\"/legacy\"}",
            "publication", "{\"injectionEnabled\":false}"))); seed(config);
        when(settings.fetch("resources", FontService.Options.class)).thenReturn(
            Optional.of(new FontService.Options("khon-font-book-local", "/legacy")));
        var catalog = service.catalog(1, 20, "");
        assertNotNull(store.get(Policy.class).get("khon-font-book-local"), "New installation must prepare its own policy");
        assertEquals("khon-font-book-local", catalog.policyName());
        assertEquals("{\"injectionEnabled\":false}", config.getData().get("publication"));
        assertTrue(config.getData().get("resources").contains("/legacy"));
    }

    @Test void catalogDoesNotReportStorageReadyWhenAttachmentListingFails() {
        when(client.list(eq(Attachment.class), any(), any())).thenThrow(new IllegalStateException("Storage temporarily unavailable"));
        var catalog = service.catalog(1, 20, "");
        assertEquals("", catalog.policyName());
        assertFalse(catalog.storageMessage().isBlank());
    }

    @Test void ttfUploadKeepsActualFormatAndRejectsRenamedFiles() {
        byte[] ttf = {0, 1, 0, 0};
        System.arraycopy(ttf, 0, bytes, 0, ttf.length); // Mock attachment server returns the uploaded bytes.
        var result = service.importFile(new SimpleFilePart("test.ttf",
            Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(ttf)), MediaType.APPLICATION_OCTET_STREAM), fields(), "tester");
        assertNotNull(result.face());
        var part = org.mockito.ArgumentCaptor.forClass(org.springframework.http.codec.multipart.FilePart.class);
        verify(uploads).upload(anyString(), anyString(), isNull(), part.capture(), any());
        assertTrue(part.getValue().filename().endsWith(".ttf"));
        assertEquals("font/ttf", part.getValue().headers().getContentType().toString());
        var error = assertThrows(ResponseStatusException.class, () -> service.importFile(new SimpleFilePart("fake.woff2",
            Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(ttf)), MediaType.parseMediaType("font/woff2")), fields(), "tester"));
        assertEquals(400, error.getStatusCode().value());
    }

    @Test void concurrentImportIsRejectedAndRetryKeepsOneAssetAndOneFace() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var metadata = new FontMetadata("Test", "Regular", 400, "normal", 1, 2, 0, "test", List.of(new FontMetadata.CoverageRange(65, 65)));
        when(parser.parse(anyString(), any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Import was not released");
            return metadata;
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(this::upload);
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var second = pool.submit(() -> assertThrows(ResponseStatusException.class, this::upload));
                var busy = second.get(1, TimeUnit.SECONDS);
                assertEquals(503, busy.getStatusCode().value());
                assertTrue(busy.getReason().startsWith("PARSER_BUSY:"));
            } finally { release.countDown(); }
            var a = first.get(10, TimeUnit.SECONDS); var b = upload();
            assertEquals(a.face().getMetadata().getName(), b.face().getMetadata().getName());
            assertNotEquals(a.duplicate(), b.duplicate());
            assertEquals(1, store.get(FontAsset.class).size());
            assertEquals(1, store.get(FontFace.class).size());
            verify(uploads, times(1)).upload(anyString(), anyString(), isNull(), any(org.springframework.http.codec.multipart.FilePart.class), any());
        }
    }
    @Test void referencedFamilyAndAssetCannotBeDeleted() {
        var face = upload().face();
        var family = (FontFamily) store.get(FontFamily.class).get(face.getSpec().getFamilyId());
        var asset = (FontAsset) store.get(FontAsset.class).get(face.getSpec().getAssetId());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.delete("family", family.getMetadata().getName(), 0)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.delete("asset", asset.getMetadata().getName(), asset.getMetadata().getVersion())).getStatusCode().value());
        verify(client, never()).delete(any());
    }
    @Test void failedImportRetainsAttachmentAndRetryRepairsRelations() {
        failFace.set(true);
        assertEquals(503, assertThrows(ResponseStatusException.class, this::upload).getStatusCode().value());
        var asset = (FontAsset) store.get(FontAsset.class).values().iterator().next();
        assertEquals("FAILED", asset.getStatus().getState());
        assertTrue(store.get(FontFamily.class).isEmpty(), "Unreferenced family from failed attempt is compensated");
        verify(uploads, never()).delete(any());
        failFace.set(false);
        var result = service.importExisting(new FontService.ExistingImport("attachment", "", "Source", "Permission", ""));
        assertTrue(result.duplicate());
        assertEquals(asset.getMetadata().getName(), result.face().getSpec().getAssetId());
        assertEquals("READY", asset.getStatus().getState());
        assertEquals(1, store.get(FontFamily.class).size());
    }
    @Test void staleVersionCannotOverwriteFamily() {
        var family = service.createFamily(new FontService.FamilyUpdate(null, "Original", true));
        assertEquals(409, assertThrows(ResponseStatusException.class,
            () -> service.updateFamily(family.getMetadata().getName(), new FontService.FamilyUpdate(99L, "Changed", false))).getStatusCode().value());
        assertEquals("Original", family.getSpec().getDisplayName());
    }
    @Test void existingAttachmentFromAnotherPolicyIsForbidden() {
        ((Attachment) store.get(Attachment.class).get("attachment")).getSpec().setPolicyName("default-policy");
        assertEquals(403, assertThrows(ResponseStatusException.class,
            () -> service.importExisting(new FontService.ExistingImport("attachment", "", "Source", "Permission", ""))).getStatusCode().value());
        verifyNoInteractions(parser);
    }
    @Test void missingAttachmentCannotProducePreviewBytes() {
        var face = upload().face();
        store.get(Attachment.class).clear();
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.preview(face.getMetadata().getName())).getStatusCode().value());
    }
    @Test void deletingRecordsDoesNotDeletePhysicalAttachment() {
        var face = upload().face();
        service.delete("face", face.getMetadata().getName(), face.getMetadata().getVersion());
        var asset = (FontAsset) store.get(FontAsset.class).get(face.getSpec().getAssetId());
        service.delete("asset", asset.getMetadata().getName(), asset.getMetadata().getVersion());
        assertFalse(store.get(Attachment.class).isEmpty());
        verify(uploads, never()).delete(any());
    }

    private FontFace cjkFace() {
        var family = service.createFamily(new FontService.FamilyUpdate(null, "CJK", true));
        var face = new FontFace(); face.setMetadata(meta("face-cjk")); face.setSpec(new FontFace.FaceSpec());
        face.getSpec().setFamilyId(family.getMetadata().getName());
        face.getSpec().setAssetId(((FontAsset) store.get(FontAsset.class).values().iterator().next()).getMetadata().getName());
        face.getSpec().setMetadata(new FontMetadata("CJK", "Regular", 400, "normal", 5, 6, 0, "4.67.0", List.of(
            new FontMetadata.CoverageRange(65, 65), new FontMetadata.CoverageRange(0x2014, 0x2014),
            new FontMetadata.CoverageRange(0x3002, 0x3002), new FontMetadata.CoverageRange(0x4e2d, 0x4e2d),
            new FontMetadata.CoverageRange(0xff0c, 0xff0c))));
        seed(face); return face;
    }

    private FontService.SchemeUpdate schemeRequest(FontFace latin, FontFace cjk, Long version, boolean enabled) {
        return new FontService.SchemeUpdate(version, "Mixed", enabled, latin.getSpec().getFamilyId(),
            cjk.getSpec().getFamilyId(), "latin-cjk-v1", 400, "normal", List.of("Microsoft YaHei", "sans-serif"));
    }

    @Test void schemeAllocatesActualCoverageAndBlocksFamilyFaceAndMoveUntilReferenceChanges() {
        var latin = upload().face(); var cjk = cjkFace();
        var scheme = service.saveScheme(null, schemeRequest(latin, cjk, null, true));
        var id = scheme.getMetadata().getName(); var plan = service.schemePreview(id);
        var latinRanges = plan.faces().stream().filter(f -> f.role().equals("latin")).findFirst().orElseThrow().unicodeRanges();
        var cjkRanges = plan.faces().stream().filter(f -> f.role().equals("cjk")).findFirst().orElseThrow().unicodeRanges();
        assertEquals(List.of(new FontMetadata.CoverageRange(65, 65)), latinRanges);
        assertTrue(cjkRanges.stream().anyMatch(r -> r.start() == 0x4e2d));
        assertFalse(cjkRanges.stream().anyMatch(r -> r.start() == 65 || r.start() == 0x2014));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.delete("face", cjk.getMetadata().getName(), 0)).getStatusCode().value());
        assertTrue(service.references("family", cjk.getSpec().getFamilyId()).items().stream().anyMatch(r -> r.kind().equals("scheme")));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.updateFace(cjk.getMetadata().getName(),
            new FontService.FaceUpdate(0L, latin.getSpec().getFamilyId(), true))).getStatusCode().value());
        var disabled = service.saveScheme(id, schemeRequest(latin, cjk, scheme.getMetadata().getVersion(), false));
        assertTrue(service.schemePreview(id).faces().isEmpty());
        assertFalse(service.references("face", cjk.getMetadata().getName()).items().isEmpty(), "Disabled schemes retain references");
        service.delete("scheme", id, disabled.getMetadata().getVersion());
        assertTrue(service.references("face", cjk.getMetadata().getName()).items().isEmpty());
    }

    @Test void schemeRejectsUnavailableWeightAndCssFallbackWithoutCreatingRecords() {
        var latin = upload().face(); var cjk = cjkFace();
        var good = schemeRequest(latin, cjk, null, true);
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> service.saveScheme(null,
            new FontService.SchemeUpdate(null, "Bad", true, good.latinFamilyId(), good.cjkFamilyId(), good.rangePolicy(),
                700, "normal", good.fallbacks()))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.saveScheme(null,
            new FontService.SchemeUpdate(null, "Bad", true, good.latinFamilyId(), good.cjkFamilyId(), good.rangePolicy(),
                400, "normal", List.of("Arial; color:red", "sans-serif")))).getStatusCode().value());
        assertTrue(store.getOrDefault(FontScheme.class, Map.of()).isEmpty());
    }

    @Test void schemeRevisionChangesAfterFontDisableAndMissingAttachmentFallsBack() {
        var latin = upload().face(); var cjk = cjkFace();
        var scheme = service.saveScheme(null, schemeRequest(latin, cjk, null, true));
        var plan = service.schemePreview(scheme.getMetadata().getName());
        assertArrayEquals(bytes, service.schemePreviewFont(scheme.getMetadata().getName(), cjk.getMetadata().getName(), plan.revision()));
        service.updateFace(cjk.getMetadata().getName(), new FontService.FaceUpdate(0L, cjk.getSpec().getFamilyId(), false));
        var updated = service.schemePreview(scheme.getMetadata().getName());
        assertNotEquals(plan.revision(), updated.revision());
        assertFalse(updated.faces().stream().anyMatch(f -> f.role().equals("cjk")));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.schemePreviewFont(scheme.getMetadata().getName(),
            cjk.getMetadata().getName(), plan.revision())).getStatusCode().value());
        store.get(Attachment.class).clear();
        assertTrue(service.schemePreview(scheme.getMetadata().getName()).faces().isEmpty());
        assertFalse(service.schemePreview(scheme.getMetadata().getName()).diagnostics().isEmpty());
    }

    @Test void schemeUpdateAtomicallyReplacesReferencesAndRejectsStaleVersion() {
        var latin = upload().face(); var cjk = cjkFace();
        var scheme = service.saveScheme(null, schemeRequest(latin, cjk, null, true));
        var replacement = service.createFamily(new FontService.FamilyUpdate(null, "Replacement draft", false));
        var request = schemeRequest(latin, cjk, scheme.getMetadata().getVersion(), false);
        service.saveScheme(scheme.getMetadata().getName(), new FontService.SchemeUpdate(request.version(), "Updated", false,
            request.latinFamilyId(), replacement.getMetadata().getName(), request.rangePolicy(), 400, "normal", request.fallbacks()));
        assertTrue(service.references("face", cjk.getMetadata().getName()).items().isEmpty());
        assertTrue(service.references("family", replacement.getMetadata().getName()).items().stream().anyMatch(r -> r.id().equals(scheme.getMetadata().getName())));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.saveScheme(scheme.getMetadata().getName(),
            schemeRequest(latin, cjk, 0L, true))).getStatusCode().value());
        assertEquals(replacement.getMetadata().getName(), scheme.getSpec().getCjkFamilyId());
    }

    @Test void overlappingSubsetsHaveStableNonOverlappingPreviewRanges() {
        var latin = upload().face(); var cjk = cjkFace();
        var extra = new FontFace(); extra.setMetadata(meta("face-cjk-z")); extra.setSpec(new FontFace.FaceSpec());
        extra.getSpec().setFamilyId(cjk.getSpec().getFamilyId()); extra.getSpec().setAssetId(cjk.getSpec().getAssetId());
        extra.getSpec().setMetadata(cjk.getSpec().getMetadata()); seed(extra);
        var scheme = service.saveScheme(null, schemeRequest(latin, cjk, null, true));
        assertEquals(1, service.schemePreview(scheme.getMetadata().getName()).faces().stream().filter(f -> f.role().equals("cjk")).count());
    }


    private FontService.ExternalUpdate claim(String kind, String id) {
        return new FontService.ExternalUpdate(null, kind, id, "content-v1", null, null, null);
    }
    @Test void publicContractFiltersDisabledMissingAndManagementFieldsAndPublishesWithoutHeadSwitch() {
        var face = upload().face(); var id = face.getSpec().getFamilyId();
        var dto = service.publicFamily(id);
        var json = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(dto);
        for (var secret : List.of("attachmentName", "ownerName", "policyName", "parserExecutable", "parserVersion", "metadata", "apiVersion")) {
            assertFalse(json.contains("\"" + secret + "\""));
        }
        assertEquals(400, dto.faces().getFirst().weight());
        var styles = new FontStyles(service, settings); styles.start();
        var manifest = styles.integration("family", id);
        assertEquals("kfb-" + id, manifest.className());
        assertTrue(styles.find(manifest.revision() + ".css").isPresent());
        assertTrue(styles.find(manifest.revision() + ".css").orElseThrow().css().contains("." + manifest.className()));
        assertFalse(styles.injectionEnabled());
        var asset = (FontAsset) store.get(FontAsset.class).values().iterator().next();
        service.updateAsset(asset.getMetadata().getName(), new FontService.AssetUpdate(asset.getMetadata().getVersion(), "New source", "Test permission", "Changed"));
        assertNotEquals(dto.revision(), service.publicFamily(id).revision());
        assertEquals(manifest.revision(), styles.integration("family", id).revision(), "Non-CSS metadata must not churn immutable CSS");
        service.updateFamily(id, new FontService.FamilyUpdate(0L, "Disabled", false));
        assertTrue(service.publicFamilies(1, 20).items().isEmpty());
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.publicFamily(id)).getStatusCode().value());
        assertTrue(styles.find(manifest.revision() + ".css").isPresent(), "Old URL retains exact old bytes");
        styles.stop();
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> styles.integration("family", id)).getStatusCode().value());
    }
    @Test void publicSchemeRequiresBothSidesAndUsesExistingCharacterAllocation() {
        var latin = upload().face(); var cjk = cjkFace();
        var scheme = service.saveScheme(null, schemeRequest(latin, cjk, null, true));
        var dto = service.publicScheme(scheme.getMetadata().getName());
        assertEquals("latin-cjk-v1", dto.rangePolicy());
        var styles = new FontStyles(service, settings); styles.start();
        var manifest = styles.integration("scheme", dto.id());
        var css = styles.find(manifest.revision() + ".css").orElseThrow().css();
        assertTrue(css.contains("U+41-41")); assertTrue(css.contains("U+4e2d-4e2d"));
        service.updateFace(cjk.getMetadata().getName(), new FontService.FaceUpdate(0L, cjk.getSpec().getFamilyId(), false));
        assertTrue(service.publicSchemes(1, 20).items().isEmpty());
        store.get(Attachment.class).clear();
        assertTrue(service.publicFamilies(1, 20).items().isEmpty());
    }
    @Test void externalClaimsAreSourceScopedIdempotentAndProtectFamilyFacesAndMoves() {
        var face = upload().face(); String id = face.getSpec().getFamilyId();
        var first = service.registerReference("source-a", "post:one", claim("family", id));
        assertEquals(first, service.registerReference("source-a", "post:one", claim("family", id)));
        var other = service.registerReference("source-b", "post:one", claim("family", id));
        assertEquals(2, store.get(FontReference.class).size());
        assertEquals(1, service.externalReferences("source-a").items().size());
        service.unregisterReference("source-b", "post:one", other.version());
        assertEquals(1, service.externalReferences("source-a").items().size());
        service.unregisterReference("source-b", "post:one", other.version()); // Does not touch source-a.
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.delete("face", face.getMetadata().getName(), 0)).getStatusCode().value());
        var replacement = service.createFamily(new FontService.FamilyUpdate(null, "Empty", true));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.updateFace(face.getMetadata().getName(),
            new FontService.FaceUpdate(0L, replacement.getMetadata().getName(), true))).getStatusCode().value());
        assertEquals(401, assertThrows(ResponseStatusException.class, () -> service.registerReference("anonymousUser", "x", claim("family", id))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.registerReference("source-a", "../x", claim("family", id))).getStatusCode().value());
        service.unregisterReference("source-a", "post:one", first.version());
        service.delete("face", face.getMetadata().getName(), 0);
    }
    @Test void externalMigrationRequiresOldIdentityVersionAndNewContentConfirmation() {
        var latin = upload().face(); var cjk = cjkFace();
        String oldId = latin.getSpec().getFamilyId(), newId = cjk.getSpec().getFamilyId();
        var old = service.registerReference("writer", "post:one", claim("family", oldId));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.registerReference("writer", "post:one", claim("family", newId))).getStatusCode().value());
        var bad = new FontService.ExternalUpdate(old.version(), "family", newId, "content-v2", "family", "wrong", "CONTENT_UPDATED");
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.registerReference("writer", "post:one", bad)).getStatusCode().value());
        assertEquals(old, service.externalReferences("writer").items().getFirst());
        var good = new FontService.ExternalUpdate(old.version(), "family", newId, "content-v2", "family", oldId, "CONTENT_UPDATED");
        var updated = service.registerReference("writer", "post:one", good);
        assertEquals(updated, service.registerReference("writer", "post:one", good), "Retry after successful migration is idempotent");
        assertEquals(newId, updated.targetId());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.unregisterReference("writer", "post:one", old.version())).getStatusCode().value());
        assertTrue(service.references("face", latin.getMetadata().getName()).items().isEmpty());
        assertTrue(service.references("face", cjk.getMetadata().getName()).items().stream().anyMatch(r -> r.kind().equals("external")));
    }
    @Test void replacementUpdatesOnlyOwnedRelationsAndRetainsExternalClaimsUntilSourceConfirms() {
        ruleContext(); var latin = upload().face(); var cjk = cjkFace();
        var scheme = service.saveScheme(null, schemeRequest(latin, cjk, null, true));
        String oldId = latin.getSpec().getFamilyId(), newId = cjk.getSpec().getFamilyId();
        var rule = service.saveRule(null, ruleRequest(null, "family", oldId, FontRule.Scope.THEME_ALL, "", ".target", 400, 0, false, true));
        var claim = service.registerReference("writer", "post:one", claim("family", oldId));
        var request = new FontService.Replacement("family", oldId, newId, 0L, 0L);
        var result = service.replaceInternal(request);
        assertEquals("EXTERNAL_CONFIRMATION_REQUIRED", result.state());
        assertEquals(2, result.updated().size());
        assertEquals(newId, scheme.getSpec().getLatinFamilyId()); assertEquals(newId, rule.getSpec().getTargetId());
        assertEquals(oldId, service.externalReferences("writer").items().getFirst().targetId());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.delete("face", latin.getMetadata().getName(), 0)).getStatusCode().value());
        assertTrue(service.replaceInternal(request).updated().isEmpty(), "Retry does not rewrite migrated internal records");
        service.registerReference("writer", "post:one", new FontService.ExternalUpdate(claim.version(), "family", newId, "content-v2", "family", oldId, "CONTENT_UPDATED"));
        assertEquals("INTERNAL_COMPLETE", service.replaceInternal(request).state());
        service.delete("face", latin.getMetadata().getName(), 0);
        service.delete("family", oldId, 0);
        // A scheme claim protects the scheme itself, including when disabled.
        var external = service.registerReference("writer", "post:scheme", claim("scheme", scheme.getMetadata().getName()));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.delete("scheme", scheme.getMetadata().getName(), scheme.getMetadata().getVersion())).getStatusCode().value());
        assertEquals("writer", external.sourceId());
    }
    @Test void replacementPreflightRejectsIncompatibleVariantsAndPartialWorkIsResumable() {
        ruleContext(); var latin = upload().face(); var cjk = cjkFace();
        String incompatibleOldId = cjk.getSpec().getFamilyId();
        var scheme = service.saveScheme(null, schemeRequest(latin, cjk, null, true));
        var incompatible = new FontService.Replacement("family", incompatibleOldId, latin.getSpec().getFamilyId(), 0L, 0L);
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.replaceInternal(incompatible)).getStatusCode().value());
        assertEquals(incompatibleOldId, scheme.getSpec().getCjkFamilyId());
        String oldId = latin.getSpec().getFamilyId(), newId = cjk.getSpec().getFamilyId();
        var rule = service.saveRule(null, ruleRequest(null, "family", oldId, FontRule.Scope.THEME_ALL, "", ".target", null, 0, false, true));
        store.get(run.halo.app.core.extension.Theme.class).remove("theme-a"); // Existing external theme removed between uses.
        var request = new FontService.Replacement("family", oldId, newId, 0L, 0L);
        var partial = service.replaceInternal(request);
        assertEquals("PARTIAL", partial.state()); assertEquals(1, partial.updated().size());
        assertEquals(oldId, rule.getSpec().getTargetId()); assertEquals(newId, scheme.getSpec().getLatinFamilyId());
        ruleContext();
        var retry = service.replaceInternal(request);
        assertEquals("INTERNAL_COMPLETE", retry.state()); assertEquals(1, retry.updated().size());
        assertEquals(newId, rule.getSpec().getTargetId());
    }


    @Test void racingExternalRegistrationAndSchemeDeleteCannotLeaveDanglingClaim() throws Exception {
        var latin = upload().face(); var cjk = cjkFace();
        var scheme = service.saveScheme(null, schemeRequest(latin, cjk, null, true));
        String id = scheme.getMetadata().getName(); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var register = pool.submit(() -> {
                gate.await();
                try { service.registerReference("writer", "post:race", claim("scheme", id)); return 0; }
                catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
            });
            var delete = pool.submit(() -> {
                gate.await();
                try { service.delete("scheme", id, 0); return 0; }
                catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
            });
            gate.countDown(); int a = register.get(10, TimeUnit.SECONDS), b = delete.get(10, TimeUnit.SECONDS);
            assertTrue(a == 0 && b == 409 || a == 404 && b == 0);
            assertTrue(service.externalReferences("writer").items().isEmpty() || store.get(FontScheme.class).containsKey(id));
        }
    }

    private void ruleContext() {
        var theme = new run.halo.app.core.extension.Theme(); theme.setMetadata(meta("theme-a"));
        theme.setSpec(new run.halo.app.core.extension.Theme.ThemeSpec()); theme.getSpec().setDisplayName("Theme A"); seed(theme);
        var other = new run.halo.app.core.extension.Theme(); other.setMetadata(meta("theme-b"));
        other.setSpec(new run.halo.app.core.extension.Theme.ThemeSpec()); other.getSpec().setDisplayName("Theme B"); seed(other);
        for (var id : List.of("post-a", "post-b", "draft")) {
            var post = new run.halo.app.core.extension.content.Post(); post.setMetadata(meta(id));
            post.getMetadata().setLabels(Map.of("content.halo.run/published", id.equals("draft") ? "false" : "true"));
            post.setSpec(new run.halo.app.core.extension.content.Post.PostSpec()); post.getSpec().setTitle(id);
            post.getSpec().setVisible(run.halo.app.core.extension.content.Post.VisibleEnum.PUBLIC); seed(post);
        }
        var page = new run.halo.app.core.extension.content.SinglePage(); page.setMetadata(meta("page-a"));
        page.getMetadata().setLabels(Map.of("content.halo.run/published", "true"));
        page.setSpec(new run.halo.app.core.extension.content.SinglePage.SinglePageSpec()); page.getSpec().setTitle("Page A");
        page.getSpec().setVisible(run.halo.app.core.extension.content.Post.VisibleEnum.PUBLIC); seed(page);
    }
    private FontService.RuleUpdate ruleRequest(Long version, String targetKind, String target, FontRule.Scope scope,
        String content, String selector, Integer weight, int order, boolean force, boolean enabled) {
        return new FontService.RuleUpdate(version, "Test rule", enabled, "theme-a", scope, content, selector, targetKind, target, weight, order, force);
    }
    @Test void homeScopeIsSeparateFromPostsOtherPagesAndOtherThemes() {
        ruleContext(); var target = upload().face().getSpec().getFamilyId();
        var home = service.saveRule(null, ruleRequest(null, "family", target, FontRule.Scope.HOME, "", ".headline", 400, 0, false, true));
        assertEquals(List.of(home.getMetadata().getName()), service.compile(new FontService.CompileContext("theme-a", "Home", "")).ruleIds());
        for (var context : List.of(new FontService.CompileContext("theme-a", "Unknown", ""),
            new FontService.CompileContext("theme-a", "Post", "post-a"), new FontService.CompileContext("theme-b", "Home", ""))) {
            assertTrue(service.compile(context).ruleIds().isEmpty());
        }
        assertThrows(ResponseStatusException.class, () -> service.compile(new FontService.CompileContext("theme-a", "Home", "post-a")));
        assertThrows(ResponseStatusException.class, () -> service.saveRule(null, ruleRequest(null, "family", target, FontRule.Scope.HOME, "page-a", ".headline", null, 0, false, true)));
    }
    @Test void rulesCompileOnlyMatchingThemeAndPublishedStableContentInDeterministicOrder() {
        ruleContext(); var face = upload().face(); var target = face.getSpec().getFamilyId();
        var all = service.saveRule(null, ruleRequest(null, "family", target, FontRule.Scope.THEME_ALL, "", ".post-title", null, 10, false, true));
        var one = service.saveRule(null, ruleRequest(null, "family", target, FontRule.Scope.POST_ONE, "post-a", "#stable-heading", 400, 20, true, true));
        var post = service.saveRule(null, ruleRequest(null, "family", target, FontRule.Scope.POST_ALL, "", "article h2", null, 10, false, true));
        var page = service.saveRule(null, ruleRequest(null, "family", target, FontRule.Scope.PAGE_ONE, "page-a", "#page-heading", null, 30, false, true));
        service.saveRule(null, ruleRequest(null, "family", target, FontRule.Scope.THEME_ALL, "", ".disabled", null, 0, false, false));
        var a = service.compile(new FontService.CompileContext("theme-a", "Post", "post-a"));
        var ties = List.of(all.getMetadata().getName(), post.getMetadata().getName()).stream().sorted().toList();
        assertEquals(List.of(ties.get(0), ties.get(1), one.getMetadata().getName()), a.ruleIds());
        assertFalse(a.css().contains(".disabled")); assertFalse(a.css().contains("page-heading"));
        assertTrue(a.css().contains("!important")); assertTrue(a.matching().contains("未读取 DOM"));
        assertEquals(a.sha256(), FontService.hash(a.css().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(a, service.compile(new FontService.CompileContext("theme-a", "Post", "post-a")));
        assertEquals(2, service.compile(new FontService.CompileContext("theme-a", "Post", "post-b")).ruleIds().size());
        assertEquals(List.of(all.getMetadata().getName()), service.compile(new FontService.CompileContext("theme-a", "Unknown", "")).ruleIds());
        assertEquals(2, service.compile(new FontService.CompileContext("theme-a", "SinglePage", "page-a")).ruleIds().size());
        assertTrue(service.compile(new FontService.CompileContext("theme-b", "Post", "post-a")).ruleIds().isEmpty());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.compile(new FontService.CompileContext("theme-a", "Post", "draft"))).getStatusCode().value());
        assertEquals(2, service.ruleOptions("Post", 1, 10, "").total());
        assertEquals("page-a", service.ruleOptions("SinglePage", 1, 10, "").contents().getFirst().id());
        service.updateFace(face.getMetadata().getName(), new FontService.FaceUpdate(0L, target, false));
        assertTrue(service.compile(new FontService.CompileContext("theme-a", "Post", "post-a")).ruleIds().isEmpty());
        assertNotEquals(a.sha256(), service.compile(new FontService.CompileContext("theme-a", "Post", "post-a")).sha256());
        assertNotNull(page);
    }
    @Test void rulesProtectReferencesAcrossDisableUpdateAndDeleteAndRejectInvalidWeight() {
        ruleContext(); var face = upload().face(); var target = face.getSpec().getFamilyId(); var cjk = cjkFace();
        var scheme = service.saveScheme(null, schemeRequest(face, cjk, null, true));
        var r = service.saveRule(null, ruleRequest(null, "family", target, FontRule.Scope.THEME_ALL, "", ".title", null, 0, false, false));
        assertTrue(service.references("face", face.getMetadata().getName()).items().stream().anyMatch(ref -> ref.kind().equals("rule")));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.delete("face", face.getMetadata().getName(), 0)).getStatusCode().value());
        service.saveRule(r.getMetadata().getName(), ruleRequest(0L, "scheme", scheme.getMetadata().getName(), FontRule.Scope.THEME_ALL, "", ".title", 400, 0, false, true));
        assertFalse(service.references("family", target).items().stream().anyMatch(ref -> ref.kind().equals("rule")));
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.delete("scheme", scheme.getMetadata().getName(), 0)).getStatusCode().value());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.saveRule(r.getMetadata().getName(),
            ruleRequest(0L, "scheme", scheme.getMetadata().getName(), FontRule.Scope.THEME_ALL, "", ".title", 400, 0, false, true))).getStatusCode().value());
        assertEquals(422, assertThrows(ResponseStatusException.class, () -> service.saveRule(null,
            ruleRequest(null, "family", target, FontRule.Scope.THEME_ALL, "", ".title", 700, 0, false, true))).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class, () -> service.saveRule(null,
            ruleRequest(null, "family", target, FontRule.Scope.POST_ONE, "draft", ".title", null, 0, false, true))).getStatusCode().value());
        service.delete("rule", r.getMetadata().getName(), 1);
        assertTrue(service.references("scheme", scheme.getMetadata().getName()).items().isEmpty());
    }
    @Test void schemeRulesReuseCharacterAllocationAndDegradeAfterMissingResources() {
        ruleContext(); var latin = upload().face(); var cjk = cjkFace();
        var scheme = service.saveScheme(null, schemeRequest(latin, cjk, null, true));
        service.saveRule(null, ruleRequest(null, "scheme", scheme.getMetadata().getName(), FontRule.Scope.THEME_ALL, "", ".mixed", null, 0, false, true));
        var result = service.compile(new FontService.CompileContext("theme-a", "Unknown", ""));
        assertTrue(result.css().contains("U+41-41")); assertTrue(result.css().contains("U+4e2d-4e2d"));
        store.get(Attachment.class).clear();
        assertTrue(service.compile(new FontService.CompileContext("theme-a", "Unknown", "")).ruleIds().isEmpty());
        assertFalse(service.rules(1, 10, "").items().getFirst().diagnostics().isEmpty());
    }

    @Test void racingRuleCreationAndFamilyDeleteCannotLeaveDanglingReference() throws Exception {
        ruleContext(); var target = service.createFamily(new FontService.FamilyUpdate(null, "Rule race", false));
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var create = pool.submit(() -> {
                gate.await();
                try { service.saveRule(null, ruleRequest(null, "family", target.getMetadata().getName(), FontRule.Scope.THEME_ALL, "", ".title", null, 0, false, false)); return 0; }
                catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
            });
            var deletion = pool.submit(() -> {
                gate.await();
                try { service.delete("family", target.getMetadata().getName(), 0); return 0; }
                catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
            });
            gate.countDown(); var a = create.get(10, TimeUnit.SECONDS); var b = deletion.get(10, TimeUnit.SECONDS);
            assertTrue(a == 0 && b == 409 || a == 404 && b == 0);
            assertTrue(store.getOrDefault(FontRule.class, Map.of()).isEmpty() || store.get(FontFamily.class).containsKey(target.getMetadata().getName()));
        }
    }

    @Test void racingSchemeReferenceUpdateAndFamilyDeleteCannotLeaveDanglingReference() throws Exception {
        var latin = upload().face(); var cjk = cjkFace();
        var scheme = service.saveScheme(null, schemeRequest(latin, cjk, null, true));
        var target = service.createFamily(new FontService.FamilyUpdate(null, "Race target", false));
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var update = pool.submit(() -> {
                gate.await();
                try {
                    service.saveScheme(scheme.getMetadata().getName(), new FontService.SchemeUpdate(0L, "Race", false,
                        latin.getSpec().getFamilyId(), target.getMetadata().getName(), "latin-cjk-v1", 400, "normal", List.of("sans-serif")));
                    return 0;
                } catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
            });
            var deletion = pool.submit(() -> {
                gate.await();
                try { service.delete("family", target.getMetadata().getName(), 0L); return 0; }
                catch (ResponseStatusException ex) { return ex.getStatusCode().value(); }
            });
            gate.countDown();
            var a = update.get(10, TimeUnit.SECONDS); var b = deletion.get(10, TimeUnit.SECONDS);
            assertTrue(a == 0 && b == 409 || a == 404 && b == 0);
            if (target.getMetadata().getName().equals(scheme.getSpec().getCjkFamilyId())) {
                assertTrue(store.get(FontFamily.class).containsKey(target.getMetadata().getName()));
            }
        }
    }
}

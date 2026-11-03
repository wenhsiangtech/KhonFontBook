package dev.tungwenblog.khonfontbook;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** Run against the final plugin JAR in ordinary Halo's Java environment. */
public final class ParserIntegrationCheck {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]);
        Set<Path> before = temporaryFiles();
        var parser = new FontParser();
        var results = new ArrayList<Map<String, Object>>();
        try {
            var runtime = parser.runtime("/intentionally-missing-legacy-python");
            check(runtime.available(), "Built-in runtime unavailable: " + runtime.message());
            check(runtime.python().isEmpty() && runtime.fontTools().isEmpty(), "Legacy external runtime fields should be empty");
            Map<?, ?> oracle = JSON.readValue(Files.readString(fixtures.resolve("expected.json")), Map.class);
            for (var entry : oracle.entrySet()) {
                String name = entry.getKey().toString();
                Map<?, ?> expected = (Map<?, ?>) entry.getValue();
                long start = System.nanoTime();
                var row = new LinkedHashMap<String, Object>();
                row.put("sample", name);
                try {
                    var metadata = parser.parse("/ignored-python", Files.readAllBytes(fixtures.resolve(name)));
                    check(Boolean.TRUE.equals(expected.get("accepted")), "Unexpectedly accepted " + name);
                    check(metadata.family().equals(expected.get("family")), "Family mismatch " + name);
                    check(metadata.subfamily().equals(expected.get("subfamily")), "Subfamily mismatch " + name);
                    check(metadata.weight() == ((Number) expected.get("weight")).intValue(), "Weight mismatch " + name);
                    check(metadata.style().replace(".0deg", "deg").equals(expected.get("style").toString().replace(".0deg", "deg")), "Style mismatch " + name);
                    check(metadata.characterCount() == ((Number) expected.get("characterCount")).intValue(), "Character count mismatch " + name);
                    check(metadata.numGlyphs() == ((Number) expected.get("numGlyphs")).intValue(), "Glyph count mismatch " + name);
                    check(metadata.embeddingRights() == ((Number) expected.get("embeddingRights")).intValue(), "Embedding flags mismatch " + name);
                    var digest = MessageDigest.getInstance("SHA-256");
                    int count = 0;
                    for (var range : metadata.coverageRanges()) {
                        for (int cp = range.start(); cp <= range.end(); cp++) {
                            digest.update(ByteBuffer.allocate(4).putInt(cp).array()); count++;
                        }
                    }
                    check(count == metadata.characterCount(), "Coverage count mismatch " + name);
                    check(HexFormat.of().formatHex(digest.digest()).equals(expected.get("coverageSha256")), "Coverage mismatch " + name);
                    row.put("accepted", true);
                } catch (ResponseStatusException ex) {
                    check(Boolean.FALSE.equals(expected.get("accepted")), "Unexpected rejection " + name + ": " + ex.getReason());
                    check(ex.getStatusCode().value() == 422 || ex.getStatusCode().value() == 413, "Unexpected failure status " + name);
                    row.put("accepted", false); row.put("reason", ex.getReason());
                }
                row.put("matches", true);
                row.put("elapsedMs", (System.nanoTime() - start) / 1_000_000.0);
                results.add(row);
            }
            for (boolean stop : new boolean[]{false, true}) {
                var failure = new AtomicReference<Throwable>();
                byte[] bytes = Files.readAllBytes(fixtures.resolve("noto-static.woff2"));
                Thread job = new Thread(() -> { try { parser.parse("/ignored", bytes); } catch (Throwable ex) { failure.set(ex); } });
                job.start();
                try {
                    long deadline = System.nanoTime() + 3_000_000_000L;
                    while (!hasChild() && job.isAlive() && System.nanoTime() < deadline) Thread.sleep(5);
                    check(hasChild(), "No live worker observed");
                    if (stop) parser.stop();
                    else {
                        try { parser.parse("/ignored", bytes); throw new AssertionError("Busy parser accepted a job"); }
                        catch (ResponseStatusException ex) { check(ex.getReason().startsWith("PARSER_BUSY:"), "Expected busy response"); }
                        job.interrupt();
                    }
                    job.join(3000);
                    check(!job.isAlive() && !hasChild() && failure.get() != null, "Worker not cleaned up");
                    if (stop) {
                        check(!parser.runtime("/ignored").available(), "Stopped parser reports ready");
                        parser.start();
                    }
                    check(parser.parse("/ignored", Files.readAllBytes(fixtures.resolve("normal.ttf"))).weight() == 400,
                        "Parser cannot be reused after cancel/restart");
                    results.add(Map.of("scenario", stop ? "stop-cleanup-restart" : "busy-cancel-reuse", "passed", true));
                } finally {
                    if (job.isAlive()) { job.interrupt(); parser.stop(); job.join(3000); }
                }
            }
            var completed = new java.util.concurrent.CountDownLatch(1);
            Set<Path> taskBaseline = temporaryFiles();
            byte[] normalBytes = Files.readAllBytes(fixtures.resolve("noto-static.woff2"));
            var subscription = FontEndpoint.json(() -> {
                try { return parser.parse("/ignored", normalBytes); }
                finally { completed.countDown(); }
            }).subscribe(value -> { }, error -> { });
            try {
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (!hasChild() && System.nanoTime() < deadline) Thread.sleep(5);
                check(hasChild(), "Reactive parsing did not start");
                subscription.dispose();
                check(completed.await(3, java.util.concurrent.TimeUnit.SECONDS), "Reactive cancellation did not finish");
                check(!hasChild() && temporaryFiles().equals(taskBaseline), "Reactive job left a worker or temporary files");
                results.add(Map.of("scenario", "reactive-cancel-cleanup", "passed", true));
            } finally { subscription.dispose(); }
        } finally { parser.stop(); }
        check(temporaryFiles().equals(before), "Temporary parser files remain after shutdown");
        JSON.writeValue(Path.of(args[1]).toFile(), results);
        System.out.println("FINAL_JAR_PARSER_PASS cases=" + results.size());
    }
    private static boolean hasChild() {
        try (var children = ProcessHandle.current().children()) { return children.anyMatch(ProcessHandle::isAlive); }
    }
    private static Set<Path> temporaryFiles() throws Exception {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("khon-font-")).collect(Collectors.toSet());
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

package dev.tungwenblog.khonfontbook;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class FontParser {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final long MAX_OUTPUT = 2 * 1024 * 1024;
    private static final String VERSION = "kfb-fontbox-2.0.34-woff2-1.0.2-brotli-1.2.0-v1";
    private final Semaphore slot = new Semaphore(1);
    private final Object state = new Object();
    private boolean stopped;
    private Process active;
    private ParserBundle bundle;
    private volatile RuntimeInfo ready;

    // Legacy fields remain for wire compatibility, without naming an external runtime.
    public record RuntimeInfo(boolean available, String message, String python, String fontTools) { }
    private record WorkerVersion(String parserVersion) { }
    private record ParseFailure(String error, String message) { }

    public RuntimeInfo runtime(String ignoredLegacyExecutable) {
        synchronized (state) { if (stopped) return unavailable("内置解析器已停止"); }
        RuntimeInfo current = ready;
        if (current != null) return current;
        try {
            String result = execute(null);
            if (!VERSION.equals(JSON.readValue(result, WorkerVersion.class).parserVersion()))
                throw new IOException("内置解析版本不一致");
            current = new RuntimeInfo(true, "内置 WOFF2 / TTF 解析器就绪（" + VERSION + "）", "", "");
            synchronized (state) { if (!stopped) ready = current; }
            return current;
        } catch (Exception ex) {
            return unavailable("内置解析器不可用：需要 Linux x86_64、glibc 2.38+、Java 21、系统 prlimit 和可执行临时目录");
        }
    }
    private static RuntimeInfo unavailable(String message) { return new RuntimeInfo(false, message, "", ""); }

    /** The legacy argument is deliberately ignored; there is no external fallback. */
    public FontMetadata parse(String ignoredLegacyExecutable, byte[] bytes) {
        validateInput(bytes);
        return JSON.readValue(execute(bytes), FontMetadata.class);
    }
    private static void validateInput(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件不得超过 20 MiB");
        var format = FontFormat.detect(bytes);
        if (".woff2".equals(format.extension())) {
            if (bytes.length < 48) throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FONT", "WOFF2 文件头不完整");
            var header = ByteBuffer.wrap(bytes);
            int tables = Short.toUnsignedInt(header.getShort(12));
            if (header.getInt(8) != bytes.length || header.getShort(14) != 0 || tables < 1 || tables > 256)
                throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FONT", "WOFF2 文件头或长度不合法");
            if (Integer.toUnsignedLong(header.getInt(16)) > 64 * 1024 * 1024)
                throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "EXPANDED_TOO_LARGE", "解压字体不得超过 64 MiB");
            if (header.getInt(4) != 0x00010000 && header.getInt(4) != 0x4f54544f)
                throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_FONT", "不支持字体集合或该字体格式");
        }
    }
    private String execute(byte[] input) {
        synchronized (state) { ensureRunning(); }
        if (!slot.tryAcquire()) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "PARSER_BUSY", "解析器忙，请稍后重试");
        Path directory = null;
        try {
            ParserBundle files;
            synchronized (state) {
                ensureRunning();
                if (bundle == null) bundle = ParserBundle.extract();
                files = bundle;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            directory = Files.createTempDirectory("khon-font-parse-");
            if (input == null) {
                if (!"KFB-WOFF2/1".equals(stage(List.of(files.decoder.toString(), "--version"), 8, directory, deadline, false).strip()))
                    throw new IOException("内置解码版本不一致");
                return stage(javaCommand(files, "--version"), 3, directory, deadline, true);
            }
            Path source = directory.resolve("input.font");
            Files.write(source, input);
            if (".woff2".equals(FontFormat.detect(input).extension())) {
                Path sfnt = directory.resolve("decoded.sfnt");
                stage(List.of(files.decoder.toString(), source.toString(), sfnt.toString()), 8, directory, deadline, false);
                source = sfnt;
            }
            return stage(javaCommand(files, source.toString()), 3, directory, deadline, true);
        } catch (ResponseStatusException ex) { throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "PARSER_CANCELLED", "字体解析已中断");
        } catch (Exception ex) {
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "PARSER_UNAVAILABLE", "无法启动内置解析器，请检查平台与临时目录权限");
        } finally {
            try { if (directory != null) ParserBundle.remove(directory); }
            catch (IOException ex) { org.slf4j.LoggerFactory.getLogger(FontParser.class).warn("Unable to remove parser temporary files", ex); }
            finally { slot.release(); }
        }
    }
    private static List<String> javaCommand(ParserBundle files, String argument) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xshare:off", "-Xint", "-Xms4m", "-Xmx32m", "-XX:NewRatio=9", "-XX:-UseCompressedOops",
            "-XX:-UseCompressedClassPointers", "-XX:MaxMetaspaceSize=16m", "-XX:ReservedCodeCacheSize=4m",
            "-XX:MaxDirectMemorySize=4m", "-Xss256k", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC",
            "-XX:-UsePerfData", "-jar", files.worker.toString(), argument);
    }
    private String stage(List<String> command, int cpu, Path directory, long deadline, boolean json) throws Exception {
        var arguments = new ArrayList<>(List.of("/usr/bin/prlimit", "--as=268435456", "--cpu=" + cpu + ":" + cpu));
        arguments.addAll(command);
        Path output = directory.resolve("result.json"), errors = directory.resolve("errors.txt");
        Process process;
        synchronized (state) {
            ensureRunning();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            var builder = new ProcessBuilder(arguments).redirectOutput(output.toFile()).redirectError(errors.toFile());
            for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
            builder.environment().put("MALLOC_ARENA_MAX", "1");
            active = process = builder.start();
        }
        try {
            while (!process.waitFor(50, TimeUnit.MILLISECONDS)) checkLimits(output, errors, deadline);
            checkLimits(output, errors, deadline);
            synchronized (state) { ensureRunning(); }
            String result = Files.readString(output);
            if (process.exitValue() != 0) {
                if (json && !result.isBlank()) {
                    var failure = JSON.readValue(result, ParseFailure.class);
                    throw problem(HttpStatus.UNPROCESSABLE_ENTITY, failure.error(), failure.message());
                }
                throw problem(HttpStatus.UNPROCESSABLE_ENTITY, process.exitValue() == 2 ? "INVALID_FONT" : "PARSER_LIMIT", "字体无法解析或达到资源限制");
            }
            return result;
        } finally {
            terminate(process);
            synchronized (state) { if (active == process) active = null; }
        }
    }
    private static void checkLimits(Path output, Path errors, long deadline) throws IOException {
        if (System.nanoTime() > deadline || Files.size(output) > MAX_OUTPUT || Files.size(errors) > MAX_OUTPUT)
            throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "PARSER_LIMIT", "字体解析超时或超过输出限制");
    }
    private void ensureRunning() {
        if (stopped) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "PARSER_CANCELLED", "内置解析器已停止");
    }
    private static void terminate(Process process) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            if (process.isAlive()) process.destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { process.waitFor(50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ex) { interrupted = true; }
            }
            if (process.isAlive()) throw new IOException("Parser worker has not exited");
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    public void start() {
        synchronized (state) {
            if (active != null || slot.availablePermits() == 0) throw new IllegalStateException("Previous parser job has not finished");
            stopped = false;
        }
    }
    public void stop() {
        Process process;
        synchronized (state) { stopped = true; ready = null; process = active; }
        boolean interrupted = Thread.interrupted(), acquired = false;
        try {
            if (process != null) terminate(process);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!acquired && System.nanoTime() < deadline) {
                try { acquired = slot.tryAcquire(50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ex) { interrupted = true; }
            }
            if (!acquired) throw new IOException("Parser job cleanup has not finished");
            synchronized (state) { if (bundle != null) { bundle.close(); bundle = null; } }
        } catch (IOException ex) { throw new IllegalStateException("Unable to stop font parser cleanly", ex);
        } finally {
            if (acquired) slot.release();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    static ResponseStatusException problem(HttpStatus status, String code, String message) {
        return new ResponseStatusException(status, code + ": " + message);
    }
}

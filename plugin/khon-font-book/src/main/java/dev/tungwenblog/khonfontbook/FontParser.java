package dev.tungwenblog.khonfontbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
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
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    public record RuntimeInfo(boolean available, String message, String python, String fontTools) { }
    private record Version(String python, String fontTools, String brotli) { }
    private record ParseFailure(String error, String message) { }

    public RuntimeInfo runtime(String executable) {
        try {
            var version = JSON.readValue(execute(executable, null), Version.class);
            return new RuntimeInfo(true, "静态 WOFF2 / TTF 解析环境可用", version.python(), version.fontTools());
        } catch (Exception ex) {
            return new RuntimeInfo(false, "解析环境不可用：请检查插件设置中的 Python 路径、Linux 资源限制和固定依赖版本", "", "");
        }
    }

    public FontMetadata parse(String executable, byte[] bytes) {
        if (bytes.length > MAX_BYTES) {
            throw problem(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "文件不得超过 20 MiB");
        }
        return JSON.readValue(execute(executable, bytes), FontMetadata.class);
    }

    private String execute(String executable, byte[] input) {
        Path directory = null;
        Process process = null;
        try {
            var python = Path.of(executable);
            if (!python.isAbsolute() || !Files.isExecutable(python)) {
                throw problem(HttpStatus.SERVICE_UNAVAILABLE, "PARSER_UNAVAILABLE", "Python 解释器不存在或不可执行");
            }
            directory = Files.createTempDirectory("khon-font-parse-");
            var script = directory.resolve("fontmeta.py");
            try (var resource = FontParser.class.getResourceAsStream("/fontmeta.py")) {
                if (resource == null) { throw new IOException("Missing parser script"); }
                Files.copy(resource, script);
            }
            var builder = new ProcessBuilder(executable, "-I", script.toString());
            if (input != null) {
                var file = directory.resolve("input.font");
                Files.write(file, input);
                builder.command().add(file.toString());
            }
            var output = directory.resolve("result.json");
            var errors = directory.resolve("errors.txt");
            process = builder.redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
            var deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (System.nanoTime() > deadline || Files.size(output) > MAX_OUTPUT || Files.size(errors) > MAX_OUTPUT) {
                    throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "PARSER_LIMIT", "字体解析超时或超过输出限制");
                }
            }
            if (Files.size(output) > MAX_OUTPUT || Files.size(errors) > MAX_OUTPUT) {
                throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "PARSER_LIMIT", "字体解析超过输出限制");
            }
            var json = Files.readString(output);
            if (process.exitValue() != 0) {
                if (json.isBlank()) {
                    throw problem(HttpStatus.UNPROCESSABLE_ENTITY, "PARSER_FAILED", "解析进程失败或达到资源限制，请检查文件和解析环境");
                }
                var error = JSON.readValue(json, ParseFailure.class);
                throw problem("FILE_TOO_LARGE".equals(error.error()) ? HttpStatus.PAYLOAD_TOO_LARGE
                    : HttpStatus.UNPROCESSABLE_ENTITY, error.error(), error.message());
            }
            return json;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "PARSER_CANCELLED", "字体解析已中断");
        } catch (Exception ex) {
            throw problem(HttpStatus.SERVICE_UNAVAILABLE, "PARSER_UNAVAILABLE", "无法启动或读取字体解析进程");
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
            if (directory != null) {
                try (var paths = Files.walk(directory)) {
                    for (var path : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
                } catch (IOException ex) {
                    org.slf4j.LoggerFactory.getLogger(FontParser.class).warn("Unable to remove parser temporary directory {}", directory);
                }
            }
        }
    }

    static ResponseStatusException problem(HttpStatus status, String code, String message) {
        return new ResponseStatusException(status, code + ": " + message);
    }
}

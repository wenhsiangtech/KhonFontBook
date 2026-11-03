package dev.tungwenblog.khonfontbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Properties;

/** Plugin-owned runtime files, with no executable paths supplied by settings. */
final class ParserBundle implements AutoCloseable {
    final Path directory, worker, decoder;
    private ParserBundle(Path directory, Path worker, Path decoder) {
        this.directory = directory; this.worker = worker; this.decoder = decoder;
    }
    static ParserBundle extract() throws Exception {
        if (!"Linux".equals(System.getProperty("os.name"))
            || !java.util.Set.of("amd64", "x86_64").contains(System.getProperty("os.arch"))
            || !Files.isExecutable(Path.of("/usr/bin/prlimit")))
            throw new IOException("内置解析器需要 Linux x86_64、glibc 2.38+ 和系统 prlimit");
        Path directory = Files.createTempDirectory("khon-font-runtime-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            var hashes = new Properties();
            try (var input = ParserBundle.class.getResourceAsStream("/parser/checksums.properties")) {
                if (input == null) throw new IOException("内置解析校验清单缺失");
                hashes.load(input);
            }
            Path worker = extractFile(directory, "font-worker.jar", hashes);
            Path decoder = extractFile(directory, "linux-x86_64/decoder", hashes);
            Files.setPosixFilePermissions(decoder, PosixFilePermissions.fromString("rwx------"));
            return new ParserBundle(directory, worker, decoder);
        } catch (Exception ex) { remove(directory); throw ex; }
    }
    private static Path extractFile(Path directory, String name, Properties hashes) throws Exception {
        Path target = directory.resolve(name.endsWith("decoder") ? "decoder" : "font-worker.jar");
        try (var input = ParserBundle.class.getResourceAsStream("/parser/" + name)) {
            if (input == null) throw new IOException("内置解析文件缺失");
            Files.copy(input, target);
        }
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(target)) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(hashes.getProperty(name)))
            throw new IOException("内置解析文件校验失败");
        return target;
    }
    static void remove(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
    @Override public void close() throws IOException { remove(directory); }
}

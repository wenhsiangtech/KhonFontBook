package dev.tungwenblog.khonfontbook.parser;

import java.io.FileInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.fontbox.ttf.NamingTable;
import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFParser;

/** Independent bounded metadata worker; its libraries never enter Halo's classloader. */
public final class MetadataWorker {
    public static final String VERSION = "kfb-fontbox-2.0.34-woff2-1.0.2-brotli-1.2.0-v1";

    public static void main(String[] args) {
        try {
            if (args.length == 1 && "--version".equals(args[0])) {
                System.out.println("{\"parserVersion\":" + quote(VERSION) + "}");
                return;
            }
            if (args.length != 1) throw new IllegalArgumentException("INVALID_FONT");
            System.out.println(inspect(Path.of(args[0])));
        } catch (OutOfMemoryError error) {
            fail("EXPANDED_TOO_LARGE");
        } catch (Exception error) {
            String code = error instanceof IllegalArgumentException && error.getMessage() != null && Set.of("INVALID_FONT", "UNSUPPORTED_FONT", "EXPANDED_TOO_LARGE", "PARSER_LIMIT").contains(error.getMessage())
                ? error.getMessage() : "INVALID_FONT";
            fail(code);
        }
    }

    private static void fail(String code) {
        System.out.println("{\"error\":" + quote(code) + ",\"message\":\"字体无法解析或超出支持范围\"}");
        System.exit(2);
    }

    private static String inspect(Path file) throws Exception {
        long size = Files.size(file);
        if (size > 64 * 1024 * 1024) throw new IllegalArgumentException("EXPANDED_TOO_LARGE");
        if (size < 12) throw new IllegalArgumentException("INVALID_FONT");
        byte[] bytes;
        try (var stream = new FileInputStream(file.toFile())) { bytes = stream.readNBytes(12 + 16 * 256); }
        var header = ByteBuffer.wrap(bytes);
        boolean cff = header.getInt(0) == 0x4f54544f;
        if (!cff && header.getInt(0) != 0x00010000) throw new IllegalArgumentException("INVALID_FONT");
        int tables = Short.toUnsignedInt(header.getShort(4));
        if (tables < 1 || tables > 256 || 12 + 16 * tables > bytes.length) throw new IllegalArgumentException("INVALID_FONT");
        var unsupported = Set.of("fvar", "gvar", "CFF2", "COLR", "CPAL", "CBDT", "CBLC", "sbix", "SVG ");
        for (int i = 0; i < tables; i++) {
            int at = 12 + 16 * i;
            String tag = new String(bytes, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (unsupported.contains(tag)) throw new IllegalArgumentException("UNSUPPORTED_FONT");
            long offset = Integer.toUnsignedLong(header.getInt(at + 8));
            long length = Integer.toUnsignedLong(header.getInt(at + 12));
            if (offset < 12 + 16 * tables || offset + length > size) throw new IllegalArgumentException("INVALID_FONT");
        }
        TTFParser parser = cff ? new OTFParser(false, true) : new TTFParser(false, true);
        try (var font = parser.parse(file.toFile())) {
            for (String tag : Set.of("head", "hhea", "maxp", "hmtx", "OS/2", "name", "cmap", "post"))
                if (!font.getTableMap().containsKey(tag)) throw new IllegalArgumentException("INVALID_FONT");
            if (cff ? !font.getTableMap().containsKey("CFF ") : !font.getTableMap().containsKey("glyf") || !font.getTableMap().containsKey("loca"))
                throw new IllegalArgumentException("INVALID_FONT");
            var os2 = font.getOS2Windows();
            int weight = os2.getWeightClass();
            boolean italic = (os2.getFsSelection() & 1) != 0;
            boolean oblique = os2.getVersion() >= 4 && (os2.getFsSelection() & 512) != 0;
            double angle = -font.getPostScript().getItalicAngle();
            if (weight < 1 || weight > 1000 || !Double.isFinite(angle) || angle <= -90 || angle >= 90)
                throw new IllegalArgumentException("UNSUPPORTED_FONT");
            if (italic != ((font.getHeader().getMacStyle() & 2) != 0) || (!oblique && italic != (angle != 0)))
                throw new IllegalArgumentException("INVALID_FONT");
            String style = oblique ? "oblique " + BigDecimal.valueOf(angle).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + "deg"
                : italic ? "italic" : "normal";
            int glyphs = font.getNumberOfGlyphs();
            if (glyphs < 1 || glyphs > 65535) throw new IllegalArgumentException("INVALID_FONT");
            var cmap = font.getUnicodeCmapLookup(false);
            if (cmap == null) throw new IllegalArgumentException("INVALID_FONT");
            var ranges = new StringBuilder();
            int count = 0, start = -1, end = -1;
            for (int cp = 0; cp <= 0x10ffff; cp++) {
                if (cp >= 0xd800 && cp <= 0xdfff) continue;
                int gid = cmap.getGlyphId(cp);
                if (gid < 0 || gid >= glyphs) throw new IllegalArgumentException("INVALID_FONT");
                if (gid == 0) continue;
                count++;
                if (start >= 0 && cp == end + 1) { end = cp; continue; }
                if (start >= 0) appendRange(ranges, start, end);
                start = end = cp;
            }
            if (start >= 0) appendRange(ranges, start, end);
            if (count == 0) throw new IllegalArgumentException("INVALID_FONT");
            String family = debugName(font.getNaming(), 16);
            if (family == null || family.isEmpty()) family = debugName(font.getNaming(), 1);
            if (family == null || family.isEmpty() || family.codePointCount(0, family.length()) > 160)
                throw new IllegalArgumentException("INVALID_FONT");
            return "{\"family\":" + quote(family) + ",\"subfamily\":" + quote(debugName(font.getNaming(), 2))
                + ",\"weight\":" + weight + ",\"style\":" + quote(style) + ",\"characterCount\":" + count
                + ",\"numGlyphs\":" + glyphs + ",\"embeddingRights\":" + Short.toUnsignedInt(os2.getFsType())
                + ",\"parserVersion\":" + quote(VERSION) + ",\"coverageRanges\":[" + ranges + "]}";
        }
    }

    private static void appendRange(StringBuilder ranges, int start, int end) {
        if (!ranges.isEmpty()) ranges.append(',');
        ranges.append("{\"start\":").append(start).append(",\"end\":").append(end).append('}');
        if (ranges.length() > 1_900_000) throw new IllegalArgumentException("PARSER_LIMIT");
    }

    private static String debugName(NamingTable names, int id) {
        String mac = null, any = null;
        for (var record : names.getNameRecords()) {
            if (record.getNameId() != id || record.getString() == null) continue;
            any = record.getString();
            if (record.getPlatformId() == 3 && record.getLanguageId() == 0x409 && !any.isEmpty()) return any;
            if (record.getPlatformId() == 1 && record.getLanguageId() == 0 && mac == null) mac = any;
        }
        return mac != null && !mac.isEmpty() ? mac : any;
    }

    private static String quote(String value) {
        if (value == null) return "\"\"";
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32 || Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}

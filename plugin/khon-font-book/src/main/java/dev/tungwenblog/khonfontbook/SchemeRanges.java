package dev.tungwenblog.khonfontbook;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** The versioned character allocation policy, not a font or CSS parser. */
final class SchemeRanges {
    static final String POLICY = "latin-cjk-v1";
    private static final int[][] LATIN = {{0, 0x024f}, {0x0300, 0x036f}, {0x1e00, 0x1eff}, {0x2000, 0x206f}};
    private static final int[][] CJK = {{0x2e80, 0x2fff}, {0x3000, 0x303f}, {0x3100, 0x312f},
        {0x31a0, 0x31bf}, {0x31c0, 0x31ef}, {0x3400, 0x4dbf}, {0x4e00, 0x9fff},
        {0xf900, 0xfaff}, {0xfe10, 0xfe1f}, {0xfe30, 0xfe4f}, {0xff00, 0xffef},
        {0x20000, 0x2fa1f}, {0x30000, 0x323af}};

    private SchemeRanges() { }

    static BitSet coverage(List<FontMetadata.CoverageRange> ranges, String role) {
        var actual = new BitSet(0x110000);
        for (var range : ranges) {
            if (range.start() < 0 || range.end() > 0x10ffff || range.start() > range.end()) {
                throw FontParser.problem(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_COVERAGE", "字符覆盖数据不合法，请重新校验字体");
            }
            actual.set(range.start(), range.end() + 1);
        }
        var policy = new BitSet(0x110000);
        for (var range : "latin".equals(role) ? LATIN : CJK) { policy.set(range[0], range[1] + 1); }
        actual.and(policy);
        return actual;
    }

    static List<FontMetadata.CoverageRange> ranges(BitSet points) {
        var result = new ArrayList<FontMetadata.CoverageRange>();
        for (int start = points.nextSetBit(0); start >= 0;) {
            int end = points.nextClearBit(start);
            result.add(new FontMetadata.CoverageRange(start, end - 1));
            start = points.nextSetBit(end);
        }
        return List.copyOf(result);
    }
}

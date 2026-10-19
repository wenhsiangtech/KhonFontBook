package dev.tungwenblog.khonfontbook;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemeRangesTest {
    @Test void versionedAllocationSeparatesLettersDigitsAndBothKindsOfPunctuation() {
        var coverage = List.of(new FontMetadata.CoverageRange(0, 0x10ffff));
        var latin = SchemeRanges.coverage(coverage, "latin");
        var cjk = SchemeRanges.coverage(coverage, "cjk");
        for (int p : new int[]{'A', '9', ',', 0x2014, 0x201c}) { assertTrue(latin.get(p)); assertFalse(cjk.get(p)); }
        for (int p : new int[]{0x4e2d, 0x3001, 0x3002, 0xff0c}) { assertTrue(cjk.get(p)); assertFalse(latin.get(p)); }
        for (int p : new int[]{0x3042, 0xac00, 0x1f600, 0xd800}) { assertFalse(latin.get(p)); assertFalse(cjk.get(p)); }
        var overlap = (java.util.BitSet) latin.clone(); overlap.and(cjk); assertTrue(overlap.isEmpty());
        assertEquals(latin, SchemeRanges.coverage(SchemeRanges.ranges(latin), "latin"));
    }
}

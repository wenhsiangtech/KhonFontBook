package dev.tungwenblog.khonfontbook;

import java.util.List;

public record FontMetadata(String family, String subfamily, int weight, String style,
    int characterCount, int numGlyphs, int embeddingRights, String parserVersion,
    List<CoverageRange> coverageRanges) {
    public record CoverageRange(int start, int end) { }
}

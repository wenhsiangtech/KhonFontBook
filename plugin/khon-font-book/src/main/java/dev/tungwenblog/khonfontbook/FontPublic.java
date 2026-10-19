package dev.tungwenblog.khonfontbook;

import java.util.List;

/** Deliberately separate from extension and Console models. v1alpha1 read contract. */
public final class FontPublic {
    private FontPublic() { }
    public record Face(String id, String familyName, String subfamily, int weight, String style,
        int characterCount, int glyphCount, int embeddingRights, List<FontMetadata.CoverageRange> coverageRanges,
        String contentSha256, String url, String source, String license, String category) { }
    public record Family(String id, String displayName, String revision, List<Face> faces, String stylePath) { }
    public record Scheme(String id, String displayName, String revision, String latinFamilyId, String cjkFamilyId,
        String rangePolicy, int weight, String style, List<String> fallbacks, String stylePath) { }
    public record Families(List<Family> items, int page, int size, int total) { }
    public record Schemes(List<Scheme> items, int page, int size, int total) { }
    public record Style(String apiVersion, String kind, String id, String revision, String href,
        String className, String variable, String fallback) { }
    public record Capabilities(String apiVersion, boolean injectionEnabled, String headMarker,
        String integrationMarker, String coordination, String referenceBoundary) { }
}

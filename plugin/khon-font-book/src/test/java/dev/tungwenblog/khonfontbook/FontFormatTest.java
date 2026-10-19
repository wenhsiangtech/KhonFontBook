package dev.tungwenblog.khonfontbook;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FontFormatTest {
    @Test void signatureSelectsCorrectStorageAndPreviewMime() {
        assertEquals(new FontFormat(".ttf", "font/ttf"), FontFormat.detect(new byte[] {0, 1, 0, 0}));
        assertEquals(new FontFormat(".woff2", "font/woff2"), FontFormat.detect(new byte[] {'w', 'O', 'F', '2'}));
        assertThrows(ResponseStatusException.class, () -> FontFormat.detect(new byte[] {'O', 'T', 'T', 'O'}));
        assertThrows(ResponseStatusException.class, () -> FontFormat.detect(new byte[0]));
    }
    @Test void onlyBoundedStylesCanReachCssWriter() {
        for (var style : new String[] {"normal", "italic", "oblique 12deg", "oblique -12.5deg", "oblique 0deg"}) {
            assertTrue(FontFormat.validStyle(style), style);
        }
        for (var style : new String[] {"oblique 90deg", "oblique NaNdeg", "oblique 12deg;color:red", "italic; src:url(x)"}) {
            assertFalse(FontFormat.validStyle(style), style);
        }
    }
}

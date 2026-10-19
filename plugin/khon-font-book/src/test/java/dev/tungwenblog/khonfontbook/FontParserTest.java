package dev.tungwenblog.khonfontbook;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FontParserTest {
    @Test void oversizedInputIsRejectedBeforeStartingProcess() {
        var error = assertThrows(ResponseStatusException.class,
            () -> new FontParser().parse("/missing-python", new byte[FontParser.MAX_BYTES + 1]));
        assertEquals(413, error.getStatusCode().value());
    }
    @Test void unavailableRuntimeDoesNotPretendImportIsAvailable() {
        assertFalse(new FontParser().runtime("not-an-absolute-python-path").available());
    }
}

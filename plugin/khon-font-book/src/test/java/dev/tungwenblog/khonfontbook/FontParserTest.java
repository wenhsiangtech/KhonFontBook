package dev.tungwenblog.khonfontbook;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class FontParserTest {
    @Test void invalidSignatureIsRejectedIndependentlyOfLegacyPythonSetting() {
        var error = assertThrows(ResponseStatusException.class,
            () -> new FontParser().parse("/missing-python", new byte[4]));
        assertEquals(422, error.getStatusCode().value());
        assertTrue(error.getReason().startsWith("INVALID_FONT:"));
    }
    @Test void oversizedInputIsRejectedBeforeStartingProcess() {
        var error = assertThrows(ResponseStatusException.class,
            () -> new FontParser().parse("/missing-python", new byte[FontParser.MAX_BYTES + 1]));
        assertEquals(413, error.getStatusCode().value());
    }
    @Test void unavailableRuntimeDoesNotPretendImportIsAvailable() {
        var parser = new FontParser();
        parser.stop();
        assertFalse(parser.runtime("not-an-absolute-python-path").available());
        var error = assertThrows(ResponseStatusException.class,
            () -> parser.parse("/ignored", new byte[]{0, 1, 0, 0}));
        assertTrue(error.getReason().startsWith("PARSER_CANCELLED:"));
    }
}

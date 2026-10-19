package dev.tungwenblog.khonfontbook;

import org.springframework.http.HttpStatus;

/** Actual file signature, never the uploaded MIME or display name. */
record FontFormat(String extension, String mediaType) {
    static FontFormat detect(byte[] bytes) {
        if (bytes.length >= 4) {
            if (bytes[0] == 'w' && bytes[1] == 'O' && bytes[2] == 'F' && bytes[3] == '2') {
                return new FontFormat(".woff2", "font/woff2");
            }
            if (bytes[0] == 0 && bytes[1] == 1 && bytes[2] == 0 && bytes[3] == 0) {
                return new FontFormat(".ttf", "font/ttf");
            }
        }
        throw FontParser.problem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FONT", "仅支持单字体 WOFF2 或 TrueType TTF");
    }

    static boolean validStyle(String style) {
        if ("normal".equals(style) || "italic".equals(style)) { return true; }
        if (style == null || !style.matches("oblique -?(?:0|[1-9][0-9]?)(?:\\.[0-9]{1,6})?deg")) { return false; }
        double angle = Double.parseDouble(style.substring(8, style.length() - 3));
        return angle > -90 && angle < 90;
    }
}

package com.msfg.los.documents.service;

/**
 * Minimal HTML escaping for user-originated strings interpolated into generated text/html
 * documents (pre-approval letters, lock confirmations, stub vendor reports). Escape every
 * value that originates from user input — borrower names, addresses, labels — and leave
 * server-controlled constants (enum names, minted ids) alone.
 */
public final class HtmlText {

    private HtmlText() {
    }

    /** Escapes {@code & < > " '} for safe interpolation into HTML text and attribute positions. */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

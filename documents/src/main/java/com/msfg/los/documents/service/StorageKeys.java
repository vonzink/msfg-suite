package com.msfg.los.documents.service;

import java.util.UUID;

/**
 * Storage-key construction + filename sanitization for the presigned-upload flow. Pure/static so it
 * is trivially unit-testable and shared between the prod (S3) and local key layouts.
 *
 * <p>Key layout (matches the S3 adapter's documented contract):
 * {@code applications/{loanId}/{partyRole}/{typeName-or-Other}/{docId}-{safeFilename}}.
 */
public final class StorageKeys {

    private StorageKeys() {
    }

    private static final int MAX_FILENAME = 200;

    /**
     * Sanitize a client-supplied filename to a single safe path segment:
     * <ul>
     *   <li>basename only (drop any directory components, both {@code /} and {@code \}),</li>
     *   <li>collapse runs of unsafe chars ({@code [^a-zA-Z0-9._-]+}) to a single {@code _},</li>
     *   <li>strip leading dots (no hidden/relative names),</li>
     *   <li>default to {@code "file"} when nothing safe remains,</li>
     *   <li>cap at {@value #MAX_FILENAME} chars, preserving an extension of ≤ 8 chars.</li>
     * </ul>
     */
    public static String sanitizeFilename(String raw) {
        if (raw == null) return "file";
        // basename only — guard against path traversal in the supplied name
        String base = raw;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);

        String cleaned = base.replaceAll("[^a-zA-Z0-9._-]+", "_");
        // strip leading dots (no ".", "..", or hidden names)
        cleaned = cleaned.replaceAll("^\\.+", "");
        // nothing meaningful left (blank, or only separator chars) → default
        if (cleaned.isBlank() || cleaned.replaceAll("[._-]+", "").isEmpty()) return "file";

        if (cleaned.length() <= MAX_FILENAME) return cleaned;
        return capPreservingExtension(cleaned);
    }

    private static String capPreservingExtension(String name) {
        int dot = name.lastIndexOf('.');
        String ext = (dot > 0 && name.length() - dot - 1 <= 8 && name.length() - dot - 1 >= 1)
                ? name.substring(dot) // includes the dot
                : "";
        int keep = MAX_FILENAME - ext.length();
        String stem = name.substring(0, Math.max(0, Math.min(keep, name.length())));
        if (!ext.isEmpty()) {
            // ensure the stem doesn't already swallow the extension region
            stem = name.substring(0, Math.max(0, dot));
            if (stem.length() > keep) stem = stem.substring(0, keep);
        }
        return stem + ext;
    }

    /**
     * Build the object storage key. {@code typeName} is the catalog type name (or {@code "Other"}
     * when none); {@code partyRole} is already a single short token.
     */
    public static String build(UUID loanId, String partyRole, String typeName, UUID docId, String rawFilename) {
        String role = (partyRole == null || partyRole.isBlank()) ? "unknown" : partyRole.trim();
        String type = (typeName == null || typeName.isBlank()) ? "Other" : typeName.trim();
        String safe = sanitizeFilename(rawFilename);
        return "applications/" + loanId + "/" + role + "/" + type + "/" + docId + "-" + safe;
    }
}

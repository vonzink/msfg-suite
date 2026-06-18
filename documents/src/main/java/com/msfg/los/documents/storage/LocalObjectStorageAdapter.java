package com.msfg.los.documents.storage;

import com.msfg.los.documents.domain.DocumentContent;
import com.msfg.los.documents.repo.DocumentContentRepository;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.storage.BlobStoragePort;
import com.msfg.los.platform.storage.ObjectStoragePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Local/test object-storage adapter, DB-backed via the existing {@code document_content} table.
 * Implements both {@link ObjectStoragePort} (presigned-flow seam) and {@link BlobStoragePort}
 * (generated artifacts) so a single bean serves every storage seam in the default {@code db} mode.
 * Replaces the old {@code DbDocumentStorageAdapter} (store/load/delete folded in here).
 *
 * <p>{@code presignUpload}/{@code presignDownload} return absolute URLs to internal,
 * AUTHENTICATED, driver-gated endpoints ({@link LocalBlobController}) rather than real presigned
 * URLs — there is no public object store locally. The key is base64url-encoded into a single safe
 * path segment so S3-style slash-bearing keys round-trip. {@code tag} is a no-op.
 */
@Component
@ConditionalOnProperty(name = "los.storage.driver", havingValue = "db", matchIfMissing = true)
public class LocalObjectStorageAdapter implements ObjectStoragePort, BlobStoragePort {

    /** Path prefix of the internal receive/download endpoints (under /api → authenticated). */
    public static final String BASE_PATH = "/api/_local-blob/";

    private final DocumentContentRepository contents;

    public LocalObjectStorageAdapter(DocumentContentRepository contents) {
        this.contents = contents;
    }

    /** key → single URL-safe path token. */
    public static String encodeToken(String key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    /** path token → key. */
    public static String decodeToken(String token) {
        return new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
    }

    private String absoluteUrl(String key) {
        UriComponentsBuilder b;
        try {
            b = ServletUriComponentsBuilder.fromCurrentContextPath();
        } catch (IllegalStateException noRequest) {
            // No active request (e.g. async/generated path) — fall back to a relative URL.
            b = UriComponentsBuilder.newInstance();
        }
        return b.path(BASE_PATH + encodeToken(key)).build().toUriString();
    }

    @Override
    public String presignUpload(String key, String contentType, Duration ttl) {
        return absoluteUrl(key);
    }

    @Override
    public String presignDownload(String key, String filename, Duration ttl) {
        return absoluteUrl(key);
    }

    @Override
    @Transactional(readOnly = true)
    public long headSize(String key) {
        return contents.findByStorageKey(key)
                .map(c -> c.getContent() == null ? 0L : (long) c.getContent().length)
                .orElse(-1L);
    }

    @Override
    @Transactional(readOnly = true)
    public String sha256(String key) {
        return contents.findByStorageKey(key).map(DocumentContent::getContent).map(bytes -> {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder sb = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                    sb.append(Character.forDigit(b & 0xF, 16));
                }
                return sb.toString();
            } catch (Exception e) {
                return null;
            }
        }).orElse(null);
    }

    @Override
    public void tag(String key, Map<String, String> tags) {
        // no-op locally (no object store to tag)
    }

    // --- store/load/delete: shared by ObjectStoragePort.delete + BlobStoragePort ---

    @Override
    @Transactional
    public void store(String storageKey, byte[] bytes, String contentType) {
        DocumentContent c = contents.findByStorageKey(storageKey).orElseGet(DocumentContent::new);
        c.setStorageKey(storageKey);
        c.setContent(bytes);
        contents.save(c);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] load(String storageKey) {
        return contents.findByStorageKey(storageKey).map(DocumentContent::getContent)
                .orElseThrow(() -> new NotFoundException("Document content", storageKey));
    }

    @Override
    @Transactional
    public void delete(String storageKey) {
        contents.findByStorageKey(storageKey).ifPresent(contents::delete);
    }
}

package com.msfg.los.platform.storage;

import java.time.Duration;
import java.util.Map;

/**
 * Object-storage port for the presigned-upload document flow (the testability seam).
 *
 * <p>Distinct from {@link BlobStoragePort} (store/load/delete of opaque bytes for generated
 * artifacts, which stays as-is): {@code ObjectStoragePort} adds the surface a real object store
 * (S3/GCS) exposes for browser-direct uploads/downloads — presigned URLs, server-side metadata
 * (size, sha256), and object tagging. The prod adapter is S3; the local/test adapter is DB-backed
 * and presigns to internal, authenticated, driver-gated endpoints.
 *
 * <p>Implementations must not infer tenant/loan scoping from the key — callers resolve an
 * already-authorized {@code key}. Keys are opaque, server-minted, and non-enumerable.
 */
public interface ObjectStoragePort {

    /**
     * Presign (or otherwise produce) a URL the client can PUT raw bytes to, storing them under
     * {@code key}. The returned URL is valid for {@code ttl} and expects {@code contentType}.
     * For the local adapter this is an internal, AUTHENTICATED, driver-gated receive endpoint.
     */
    String presignUpload(String key, String contentType, Duration ttl);

    /**
     * Presign (or otherwise produce) a URL the client can GET to download the bytes stored under
     * {@code key} as an attachment named {@code filename}, valid for {@code ttl}.
     */
    String presignDownload(String key, String filename, Duration ttl);

    /** Stored byte length for {@code key}, or {@code -1} if the object is absent. */
    long headSize(String key);

    /** SHA-256 hex digest of the stored bytes for {@code key}; nullable / best-effort (null on failure). */
    String sha256(String key);

    /** Attach metadata tags to the object (no-op where unsupported, e.g. the local adapter). */
    void tag(String key, Map<String, String> tags);

    /** Remove the object stored under {@code key} (idempotent — absent key is not an error). */
    void delete(String key);
}

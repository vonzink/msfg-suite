package com.msfg.los.platform.storage;

/**
 * Generic blob storage port: store / load / delete opaque byte content keyed by an
 * opaque, server-minted storage key. Lives in {@code platform} so any feature module can
 * persist generated artifacts behind a swappable adapter (DB today, S3/GCS later) without
 * coupling to a feature module.
 *
 * <p>Implementations must not infer tenant/loan scoping from the key — callers are
 * responsible for resolving an already-authorized {@code storageKey}.
 */
public interface BlobStoragePort {
    void store(String storageKey, byte[] bytes, String contentType);

    byte[] load(String storageKey); // NotFoundException if absent

    void delete(String storageKey);
}

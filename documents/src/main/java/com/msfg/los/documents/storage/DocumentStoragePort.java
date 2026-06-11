package com.msfg.los.documents.storage;

public interface DocumentStoragePort {
    void store(String storageKey, byte[] bytes, String contentType);
    byte[] load(String storageKey);   // NotFoundException if absent
    void delete(String storageKey);
}

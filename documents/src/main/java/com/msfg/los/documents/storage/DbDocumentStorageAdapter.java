package com.msfg.los.documents.storage;

import com.msfg.los.documents.domain.DocumentContent;
import com.msfg.los.documents.repo.DocumentContentRepository;
import com.msfg.los.platform.error.NotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DbDocumentStorageAdapter implements DocumentStoragePort {

    private final DocumentContentRepository contents;

    public DbDocumentStorageAdapter(DocumentContentRepository contents) {
        this.contents = contents;
    }

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

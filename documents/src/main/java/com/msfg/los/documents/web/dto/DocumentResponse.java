package com.msfg.los.documents.web.dto;

import com.msfg.los.documents.domain.Document;
import com.msfg.los.documents.domain.DocumentType;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        DocumentType documentType,
        String category,
        String fileName,
        String contentType,
        Long sizeBytes,
        Instant generatedOn,
        String requestedBy
) {
    public static DocumentResponse from(Document d) {
        return new DocumentResponse(
                d.getId(),
                d.getDocumentType(),
                d.getCategory(),
                d.getFileName(),
                d.getContentType(),
                d.getSizeBytes(),
                d.getCreatedAt(),
                d.getCreatedBy()
        );
    }
}

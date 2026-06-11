package com.msfg.los.documents.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "document")
@Getter
@Setter
public class Document extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType documentType;

    private String category;

    private String fileName;

    private String contentType;

    private Long sizeBytes;

    @Column(nullable = false)
    private String storageKey;
}

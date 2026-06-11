package com.msfg.los.documents.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "document_content")
@Getter
@Setter
public class DocumentContent extends TenantScopedEntity {

    @Column(nullable = false)
    private String storageKey;

    @Column(columnDefinition = "bytea")
    private byte[] content;
}

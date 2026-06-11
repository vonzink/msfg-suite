package com.msfg.los.coc.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "coc_draft")
@Getter
@Setter
public class CocDraft extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    private LocalDate dateOfDiscovery;

    @Enumerated(EnumType.STRING)
    private CocReason reason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<StructureChange> structureChanges = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<FeeChange> feeChanges = new ArrayList<>();
}

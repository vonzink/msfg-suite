package com.msfg.los.platform.pii;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "pii_access_log")
@Getter
@Setter
public class PiiAccessLog extends TenantScopedEntity {

    @Column(nullable = false, length = 40)
    private String subjectType;

    @Column(nullable = false)
    private UUID subjectId;

    @Column(nullable = false, length = 40)
    private String field;

    // Required: every NPI access must state a justification (audit/compliance).
    @Column(nullable = false, length = 500)
    private String reason;
}

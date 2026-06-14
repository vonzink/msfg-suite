package com.msfg.los.disclosures.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Append-only audit of a disclosure lifecycle event (issuance / receipt / reset / checks). */
@Entity
@Table(name = "disclosure_event")
@Getter
@Setter
public class DisclosureEvent extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "disclosure_id")
    private UUID disclosureId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private DisclosureEventType eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> detail = new HashMap<>();

    @Column(name = "actor", length = 120)
    private String actor;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}

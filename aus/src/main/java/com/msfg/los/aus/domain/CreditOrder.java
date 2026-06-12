package com.msfg.los.aus.domain;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One credit-order request against the credit vendor, plus its outcome (scores/report). */
@Entity
@Table(name = "credit_order")
@Getter
@Setter
public class CreditOrder extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "provider_code", length = 40)
    private String providerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private CreditOrderAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20)
    private CreditRequestType requestType;

    @Column(name = "equifax", nullable = false)
    private boolean equifax = true;

    @Column(name = "experian", nullable = false)
    private boolean experian = true;

    @Column(name = "trans_union", nullable = false)
    private boolean transUnion = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "borrower_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> borrowerIds = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CreditOrderStatus status;

    @Column(name = "credit_report_identifier", length = 120)
    private String creditReportIdentifier;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scores", nullable = false, columnDefinition = "jsonb")
    private List<CreditScoreEntry> scores = new ArrayList<>();

    @Column(name = "report_document_id")
    private UUID reportDocumentId;

    @Column(name = "requested_by", length = 120)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}

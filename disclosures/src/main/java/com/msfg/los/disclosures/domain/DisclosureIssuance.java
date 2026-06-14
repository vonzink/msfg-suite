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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One issued (or pending) disclosure — an LE or CD — versioned per kind per loan.
 * NOTE: {@code disclosureVersion} is the TRID domain version (column disclosure_version);
 * the inherited {@code version} (bigint) is the JPA @Version optimistic lock from the base entity.
 */
@Entity
@Table(name = "disclosure_issuance")
@Getter
@Setter
public class DisclosureIssuance extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private DisclosureKind kind;

    @Column(name = "disclosure_version", nullable = false)
    private int disclosureVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DisclosureStatus status;

    @Column(name = "apr", precision = 9, scale = 5)
    private BigDecimal apr;

    @Column(name = "finance_charge", precision = 15, scale = 2)
    private BigDecimal financeCharge;

    @Column(name = "amount_financed", precision = 15, scale = 2)
    private BigDecimal amountFinanced;

    @Column(name = "total_of_payments", precision = 15, scale = 2)
    private BigDecimal totalOfPayments;

    @Column(name = "tip", precision = 9, scale = 5)
    private BigDecimal tip;

    @Column(name = "apr_irregular_basis", nullable = false)
    private boolean aprIrregularBasis = false;

    @Column(name = "prepayment_penalty", nullable = false)
    private boolean prepaymentPenalty = false;

    @Column(name = "product_description", length = 120)
    private String productDescription;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", length = 20)
    private DeliveryMethod deliveryMethod;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "received_basis", length = 24)
    private ReceivedBasis receivedBasis;

    @Column(name = "computed_received_date")
    private LocalDate computedReceivedDate;

    @Column(name = "earliest_consummation_date")
    private LocalDate earliestConsummationDate;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "vendor_reference", length = 120)
    private String vendorReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", nullable = false, columnDefinition = "jsonb")
    private DisclosureSnapshot snapshot;

    @Column(name = "trigger_coc_id")
    private UUID triggerCocId;

    @Column(name = "reset_triggered", nullable = false)
    private boolean resetTriggered = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reset_reasons", nullable = false, columnDefinition = "jsonb")
    private List<ResetReason> resetReasons = new ArrayList<>();

    @Column(name = "requested_by", length = 120)
    private String requestedBy;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}

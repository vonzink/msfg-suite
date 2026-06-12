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

/** One AUS submission (DU or LPA) and its findings. */
@Entity
@Table(name = "aus_run")
@Getter
@Setter
public class AusRun extends TenantScopedEntity {

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "vendor", nullable = false, length = 10)
    private AusVendor vendor;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AusRunStatus status;

    @Column(name = "vendor_case_id", length = 120)
    private String vendorCaseId;

    @Column(name = "vendor_transaction_id", length = 120)
    private String vendorTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation", length = 40)
    private AusRecommendation recommendation;

    @Column(name = "raw_recommendation", length = 120)
    private String rawRecommendation;

    @Column(name = "raw_eligibility", length = 120)
    private String rawEligibility;

    @Column(name = "credit_report_identifier", length = 120)
    private String creditReportIdentifier;

    @Column(name = "findings_html_document_id")
    private UUID findingsHtmlDocumentId;

    @Column(name = "findings_xml_document_id")
    private UUID findingsXmlDocumentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "messages", nullable = false, columnDefinition = "jsonb")
    private List<String> messages = new ArrayList<>();

    @Column(name = "requested_by", length = 120)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;
}

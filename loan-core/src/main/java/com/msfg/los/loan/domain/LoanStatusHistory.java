package com.msfg.los.loan.domain;

import com.msfg.los.platform.domain.TenantScopedEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "loan_status_history")
@Getter
@Setter
public class LoanStatusHistory extends TenantScopedEntity {

    @Column(nullable = false)
    private UUID loanId;

    @Enumerated(EnumType.STRING)
    private LoanStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LoanStatus toStatus;

    @Column(length = 1000)
    private String reason;

    // Effective time of the transition (Phase 2 T3). Distinct from the audited createdAt write time
    // so a transition can be backdated. Null on legacy rows → seeded from createdAt by V23.
    @Column(name = "transitioned_at")
    private Instant transitionedAt;
}

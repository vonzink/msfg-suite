package com.msfg.los.loan;

import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.domain.LoanPurposeType;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.loan.web.dto.CreateLoanRequest;
import com.msfg.los.platform.tenancy.TenantContextHolder;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanSourceLeadIT extends AbstractIntegrationTest {

    @Autowired
    LoanService loans;

    static final UUID OFFICER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setTenant() {
        TenantContextHolder.set(UUID.fromString(DEFAULT_ORG));
    }

    @Test
    void tagSourceLead_roundTrips() {
        Loan loan = loans.create(new CreateLoanRequest(
                LoanPurposeType.PURCHASE, null, null, null, null, OFFICER));

        loans.tagSourceLead(loan.getId(), "lead-123");

        assertThat(loans.findBySourceLeadId("lead-123"))
                .isPresent()
                .get()
                .extracting(Loan::getId)
                .isEqualTo(loan.getId());

        assertThat(loans.findBySourceLeadId("nope")).isEmpty();
    }

    @Test
    void findBySourceLeadId_nullOrBlank_returnsEmpty() {
        assertThat(loans.findBySourceLeadId(null)).isEmpty();
        assertThat(loans.findBySourceLeadId("  ")).isEmpty();
    }

    @Test
    void tagSourceLead_uniquePerOrg_constraintFires() {
        Loan loan1 = loans.create(new CreateLoanRequest(
                LoanPurposeType.PURCHASE, null, null, null, null, OFFICER));
        Loan loan2 = loans.create(new CreateLoanRequest(
                LoanPurposeType.PURCHASE, null, null, null, null, OFFICER));

        loans.tagSourceLead(loan1.getId(), "dup-lead");

        // Second loan in the same org tagging the same sourceLeadId must violate
        // the partial unique index (org_id, source_lead_id) where source_lead_id is not null.
        assertThatThrownBy(() -> loans.tagSourceLead(loan2.getId(), "dup-lead"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

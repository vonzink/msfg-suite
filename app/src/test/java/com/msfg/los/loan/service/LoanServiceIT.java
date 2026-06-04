package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.*;
import com.msfg.los.loan.web.dto.*;
import com.msfg.los.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanServiceIT extends AbstractIntegrationTest {

    @Autowired
    LoanService service;

    static final UUID LO = UUID.randomUUID();

    @Test
    void createsLoanWithGeneratedNumberAndStartedStatus() {
        Loan loan = service.create(new CreateLoanRequest(
            LoanPurposeType.PURCHASE, MortgageType.CONVENTIONAL,
            LienPriorityType.FIRST_LIEN, AmortizationType.FIXED, null, LO));
        assertThat(loan.getLoanNumber()).startsWith("1").hasSize(10);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.STARTED);
    }

    @Test
    void transitionWritesHistoryRow() {
        Loan loan = service.create(new CreateLoanRequest(
            LoanPurposeType.PURCHASE, null, null, null, null, LO));
        service.transition(loan.getId(),
            new TransitionRequest(LoanStatus.APPLICATION_IN_PROGRESS, "start"),
            Set.of("ROLE_LO"));
        assertThat(service.history(loan.getId())).hasSize(1);
        assertThat(service.history(loan.getId()).get(0).getToStatus())
            .isEqualTo(LoanStatus.APPLICATION_IN_PROGRESS);
    }

    @Test
    void illegalTransitionRejected() {
        Loan loan = service.create(new CreateLoanRequest(
            LoanPurposeType.PURCHASE, null, null, null, null, LO));
        assertThatThrownBy(() -> service.transition(loan.getId(),
            new TransitionRequest(LoanStatus.FUNDED, "x"),
            Set.of("ROLE_ADMIN")))
            .isInstanceOf(com.msfg.los.platform.error.ConflictException.class);
    }

    @Test
    void updateSetsSubjectPropertyOnAddresslessLoan() {
        // Regression: a reloaded address-less loan has a null @Embedded SubjectProperty;
        // update must instantiate it rather than NPE.
        Loan loan = service.create(new CreateLoanRequest(
            LoanPurposeType.PURCHASE, null, null, null, null, LO));
        Loan updated = service.update(loan.getId(), new UpdateLoanRequest(
            null, null, null, null, "5345 S Flanders Way", null, "Centennial", "CO", "80015", null));
        assertThat(updated.getSubjectProperty().getCity()).isEqualTo("Centennial");
        assertThat(updated.getSubjectProperty().getState()).isEqualTo("CO");
    }
}

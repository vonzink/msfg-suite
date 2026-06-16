package com.msfg.los.financials.service;

import com.msfg.los.financials.domain.Asset;
import com.msfg.los.financials.domain.AssetType;
import com.msfg.los.financials.domain.Liability;
import com.msfg.los.financials.domain.LiabilityType;
import com.msfg.los.financials.repo.AssetRepository;
import com.msfg.los.financials.repo.LiabilityRepository;
import com.msfg.los.financials.web.dto.AddAssetRequest;
import com.msfg.los.financials.web.dto.AddLiabilityRequest;
import com.msfg.los.loan.domain.Loan;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerParty;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.platform.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the count-vs-max+1 ordinal bug (finding C1) in the financials module.
 * Stubbing the top-ordinal query to return an existing ordinal of 2 must yield 3 (max+1);
 * count-based code (which never consults the top-ordinal query) cannot produce it after a delete.
 */
@ExtendWith(MockitoExtension.class)
class FinancialsOrdinalTest {

    private static final UUID LOAN_ID = UUID.randomUUID();
    private static final UUID BORROWER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();

    @Mock AssetRepository assets;
    @Mock LiabilityRepository liabilities;
    @Mock BorrowerRepository borrowers;
    @Mock LoanService loanService;
    @Mock LoanAccessGuard accessGuard;
    @Mock TenantContext tenantContext;

    private void stubBorrowerInLoan() {
        when(tenantContext.orgId()).thenReturn(Optional.of(ORG_ID));
        when(loanService.get(LOAN_ID)).thenReturn(new Loan());
        BorrowerParty b = new BorrowerParty();
        b.setLoanId(LOAN_ID);
        when(borrowers.findByIdAndOrgId(BORROWER_ID, ORG_ID)).thenReturn(Optional.of(b));
    }

    @Test
    void assetAddUsesMaxPlusOneNotCount() {
        AssetService svc = new AssetService(assets, loanService, accessGuard, tenantContext, borrowers);
        stubBorrowerInLoan();

        Asset existingTop = new Asset();
        existingTop.setOrdinal(2);
        when(assets.findTopByBorrowerIdOrderByOrdinalDesc(BORROWER_ID)).thenReturn(Optional.of(existingTop));
        when(assets.save(any(Asset.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.add(LOAN_ID, BORROWER_ID,
                new AddAssetRequest(AssetType.CHECKING, "Bank", "1", BigDecimal.TEN, false));

        ArgumentCaptor<Asset> saved = ArgumentCaptor.forClass(Asset.class);
        verify(assets).save(saved.capture());
        assertThat(saved.getValue().getOrdinal()).isEqualTo(3);
    }

    @Test
    void liabilityAddUsesMaxPlusOneNotCount() {
        LiabilityService svc = new LiabilityService(liabilities, loanService, accessGuard, tenantContext, borrowers);
        stubBorrowerInLoan();

        Liability existingTop = new Liability();
        existingTop.setOrdinal(2);
        when(liabilities.findTopByBorrowerIdOrderByOrdinalDesc(BORROWER_ID)).thenReturn(Optional.of(existingTop));
        when(liabilities.save(any(Liability.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.add(LOAN_ID, BORROWER_ID,
                new AddLiabilityRequest(LiabilityType.REVOLVING, "Card", "1",
                        BigDecimal.TEN, BigDecimal.ONE, true, null, null));

        ArgumentCaptor<Liability> saved = ArgumentCaptor.forClass(Liability.class);
        verify(liabilities).save(saved.capture());
        assertThat(saved.getValue().getOrdinal()).isEqualTo(3);
    }
}

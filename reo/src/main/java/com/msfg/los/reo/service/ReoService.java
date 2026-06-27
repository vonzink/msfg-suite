package com.msfg.los.reo.service;

import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.tenancy.TenantContext;
import com.msfg.los.reo.domain.RealEstateOwned;
import com.msfg.los.reo.repo.RealEstateOwnedRepository;
import com.msfg.los.reo.web.dto.AddReoRequest;
import com.msfg.los.reo.web.dto.UpdateReoRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class ReoService {

    private final RealEstateOwnedRepository reo;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public ReoService(RealEstateOwnedRepository reo,
                      LoanService loanService,
                      LoanAccessGuard accessGuard,
                      TenantContext tenantContext) {
        this.reo = reo;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
    }

    private RealEstateOwned load(UUID loanId, UUID reoId) {
        return reo.findByIdAndOrgId(reoId, tenantContext.requireOrgId())
                .filter(x -> x.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("REO", reoId));
    }

    private void validate(BigDecimal marketValue,
                          BigDecimal grossMonthlyRentalIncome,
                          BigDecimal monthlyTaxes,
                          BigDecimal monthlyInsurance,
                          BigDecimal monthlyHoaDues,
                          BigDecimal monthlyMaintenance,
                          BigDecimal mortgageUnpaidBalance,
                          BigDecimal mortgageMonthlyPayment) {
        if (marketValue != null && marketValue.signum() < 0)
            throw new ValidationException("marketValue must be >= 0");
        if (grossMonthlyRentalIncome != null && grossMonthlyRentalIncome.signum() < 0)
            throw new ValidationException("grossMonthlyRentalIncome must be >= 0");
        if (monthlyTaxes != null && monthlyTaxes.signum() < 0)
            throw new ValidationException("monthlyTaxes must be >= 0");
        if (monthlyInsurance != null && monthlyInsurance.signum() < 0)
            throw new ValidationException("monthlyInsurance must be >= 0");
        if (monthlyHoaDues != null && monthlyHoaDues.signum() < 0)
            throw new ValidationException("monthlyHoaDues must be >= 0");
        if (monthlyMaintenance != null && monthlyMaintenance.signum() < 0)
            throw new ValidationException("monthlyMaintenance must be >= 0");
        if (mortgageUnpaidBalance != null && mortgageUnpaidBalance.signum() < 0)
            throw new ValidationException("mortgageUnpaidBalance must be >= 0");
        if (mortgageMonthlyPayment != null && mortgageMonthlyPayment.signum() < 0)
            throw new ValidationException("mortgageMonthlyPayment must be >= 0");
    }

    // max+1, not count — count reuses ordinals after a delete and collides
    private int nextOrdinal(UUID loanId) {
        return reo.findTopByLoanIdOrderByOrdinalDesc(loanId)
                .map(x -> x.getOrdinal() + 1)
                .orElse(0);
    }

    @Transactional
    public RealEstateOwned add(UUID loanId, AddReoRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return insert(loanId, req, req.ownerBorrowerId());
    }

    /**
     * Shared insert mechanics for {@link #add} and {@link #replaceForBorrowerInternal}: validate the 8
     * money fields, assign a loan-global {@code nextOrdinal}, copy the request fields, and persist. The
     * owner tag is passed explicitly ({@code ownerBorrowerId}) so the self-service seam can FORCE the
     * caller's borrower id rather than trusting {@code req.ownerBorrowerId()}.
     */
    private RealEstateOwned insert(UUID loanId, AddReoRequest req, UUID ownerBorrowerId) {
        validate(req.marketValue(), req.grossMonthlyRentalIncome(),
                req.monthlyTaxes(), req.monthlyInsurance(),
                req.monthlyHoaDues(), req.monthlyMaintenance(),
                req.mortgageUnpaidBalance(), req.mortgageMonthlyPayment());

        RealEstateOwned r = new RealEstateOwned();
        r.setLoanId(loanId);
        r.setOwnerBorrowerId(ownerBorrowerId);
        r.setOrdinal(nextOrdinal(loanId));
        r.setSubjectProperty(req.isSubjectProperty() != null && req.isSubjectProperty());
        r.setAddressLine1(req.addressLine1());
        r.setAddressLine2(req.addressLine2());
        r.setCity(req.city());
        r.setState(req.state());
        r.setPostalCode(req.postalCode());
        r.setPropertyType(req.propertyType());
        r.setIntendedOccupancy(req.intendedOccupancy());
        r.setPropertyStatus(req.propertyStatus());
        r.setMarketValue(req.marketValue());
        r.setGrossMonthlyRentalIncome(req.grossMonthlyRentalIncome());
        r.setMonthlyTaxes(req.monthlyTaxes());
        r.setMonthlyInsurance(req.monthlyInsurance());
        r.setMonthlyHoaDues(req.monthlyHoaDues());
        r.setMonthlyMaintenance(req.monthlyMaintenance());
        r.setMortgageUnpaidBalance(req.mortgageUnpaidBalance());
        r.setMortgageMonthlyPayment(req.mortgageMonthlyPayment());
        return reo.save(r);
    }

    /**
     * Privileged self-service write seam (Stage-2 borrower-self application): replace the caller's OWN
     * REO rows on a loan WITHOUT the staff {@code assertCanAccess} loan gate. No-guard; caller authorizes
     * via {@link LoanAccessGuard#assertBorrowerSelfWritable} (the {@code BorrowerApplicationService}
     * orchestrator asserts it before invoking this). Used ONLY by that orchestrator; public {@link #add}/
     * {@link #update}/{@link #delete} keep the staff gate unchanged.
     *
     * <p>Scope: deletes ONLY the rows tagged with this {@code borrowerId} ({@code findByLoanIdAndOwnerBorrowerId},
     * tenant-scoped via {@code @TenantId} + RLS, loaded then {@code deleteAll} — never an unscoped bulk delete),
     * so loan-level rows with a null owner or a DIFFERENT owner are left untouched. Each item is then re-added
     * through the same {@link #insert} construction + {@link #validate} the public {@code add} uses, FORCING
     * {@code ownerBorrowerId = borrowerId} and IGNORING {@code req.ownerBorrowerId()} (the body is not trusted).
     * Ordinal stays loan-global ({@code max+1}). Runs in one transaction.
     */
    @Transactional
    public List<RealEstateOwned> replaceForBorrowerInternal(UUID loanId, UUID borrowerId, List<AddReoRequest> items) {
        reo.deleteAll(reo.findByLoanIdAndOwnerBorrowerId(loanId, borrowerId));
        List<RealEstateOwned> saved = new java.util.ArrayList<>();
        for (AddReoRequest req : items) {
            saved.add(insert(loanId, req, borrowerId));
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<RealEstateOwned> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return reo.findByLoanIdOrderByOrdinalAscIdAsc(loanId);
    }

    @Transactional
    public RealEstateOwned update(UUID loanId, UUID reoId, UpdateReoRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        RealEstateOwned r = load(loanId, reoId);

        BigDecimal effectiveMarketValue = req.marketValue() != null ? req.marketValue() : r.getMarketValue();
        BigDecimal effectiveRental = req.grossMonthlyRentalIncome() != null ? req.grossMonthlyRentalIncome() : r.getGrossMonthlyRentalIncome();
        BigDecimal effectiveTaxes = req.monthlyTaxes() != null ? req.monthlyTaxes() : r.getMonthlyTaxes();
        BigDecimal effectiveInsurance = req.monthlyInsurance() != null ? req.monthlyInsurance() : r.getMonthlyInsurance();
        BigDecimal effectiveHoa = req.monthlyHoaDues() != null ? req.monthlyHoaDues() : r.getMonthlyHoaDues();
        BigDecimal effectiveMaint = req.monthlyMaintenance() != null ? req.monthlyMaintenance() : r.getMonthlyMaintenance();
        BigDecimal effectiveBalance = req.mortgageUnpaidBalance() != null ? req.mortgageUnpaidBalance() : r.getMortgageUnpaidBalance();
        BigDecimal effectivePayment = req.mortgageMonthlyPayment() != null ? req.mortgageMonthlyPayment() : r.getMortgageMonthlyPayment();

        validate(effectiveMarketValue, effectiveRental, effectiveTaxes, effectiveInsurance,
                effectiveHoa, effectiveMaint, effectiveBalance, effectivePayment);

        if (req.ownerBorrowerId() != null) r.setOwnerBorrowerId(req.ownerBorrowerId());
        if (req.isSubjectProperty() != null) r.setSubjectProperty(req.isSubjectProperty());
        if (req.addressLine1() != null) r.setAddressLine1(req.addressLine1());
        if (req.addressLine2() != null) r.setAddressLine2(req.addressLine2());
        if (req.city() != null) r.setCity(req.city());
        if (req.state() != null) r.setState(req.state());
        if (req.postalCode() != null) r.setPostalCode(req.postalCode());
        if (req.propertyType() != null) r.setPropertyType(req.propertyType());
        if (req.intendedOccupancy() != null) r.setIntendedOccupancy(req.intendedOccupancy());
        if (req.propertyStatus() != null) r.setPropertyStatus(req.propertyStatus());
        if (req.marketValue() != null) r.setMarketValue(req.marketValue());
        if (req.grossMonthlyRentalIncome() != null) r.setGrossMonthlyRentalIncome(req.grossMonthlyRentalIncome());
        if (req.monthlyTaxes() != null) r.setMonthlyTaxes(req.monthlyTaxes());
        if (req.monthlyInsurance() != null) r.setMonthlyInsurance(req.monthlyInsurance());
        if (req.monthlyHoaDues() != null) r.setMonthlyHoaDues(req.monthlyHoaDues());
        if (req.monthlyMaintenance() != null) r.setMonthlyMaintenance(req.monthlyMaintenance());
        if (req.mortgageUnpaidBalance() != null) r.setMortgageUnpaidBalance(req.mortgageUnpaidBalance());
        if (req.mortgageMonthlyPayment() != null) r.setMortgageMonthlyPayment(req.mortgageMonthlyPayment());
        return reo.save(r);
    }

    @Transactional
    public void delete(UUID loanId, UUID reoId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        RealEstateOwned r = load(loanId, reoId);
        reo.delete(r);
    }
}

package com.msfg.los.parties.service;

import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.domain.BorrowerAddress;
import com.msfg.los.parties.domain.OwnershipType;
import com.msfg.los.parties.repo.BorrowerAddressRepository;
import com.msfg.los.parties.repo.BorrowerRepository;
import com.msfg.los.parties.web.dto.AddAddressRequest;
import com.msfg.los.parties.web.dto.UpdateAddressRequest;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.reference.UsStateCode;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class BorrowerAddressService {

    private final BorrowerAddressRepository addresses;
    private final BorrowerRepository borrowers;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public BorrowerAddressService(BorrowerAddressRepository addresses, BorrowerRepository borrowers,
                                  LoanService loanService, LoanAccessGuard accessGuard, TenantContext tenantContext) {
        this.addresses = addresses;
        this.borrowers = borrowers;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    /** Verify the loan is in the caller's org + owned, and the borrower belongs to that loan. */
    private void assertBorrowerInLoan(UUID loanId, UUID borrowerId) {
        accessGuard.assertCanAccess(loanService.get(loanId));   // 404 cross-org loan, 403 not owner
        borrowers.findByIdAndOrgId(borrowerId, org())
                .filter(b -> b.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Borrower", borrowerId));
    }

    @Transactional
    public BorrowerAddress add(UUID loanId, UUID borrowerId, AddAddressRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        BorrowerAddress a = new BorrowerAddress();
        a.setBorrowerId(borrowerId);
        a.setAddressType(req.addressType());
        a.setOrdinal((int) addresses.countByBorrowerIdAndAddressType(borrowerId, req.addressType()));
        apply(a, req.addressLine1(), req.addressLine2(), req.city(), req.state(), req.postalCode(), req.country(),
                req.ownershipType(), req.residencyDurationYears(), req.residencyDurationMonths(),
                req.rentAmount(), req.rentVerified());
        if (a.getCountry() == null) a.setCountry("US");
        return addresses.save(a);
    }

    @Transactional(readOnly = true)
    public List<BorrowerAddress> list(UUID loanId, UUID borrowerId) {
        assertBorrowerInLoan(loanId, borrowerId);
        return addresses.findByBorrowerIdOrderByAddressTypeAscOrdinalAsc(borrowerId);
    }

    @Transactional
    public BorrowerAddress update(UUID loanId, UUID borrowerId, UUID addressId, UpdateAddressRequest req) {
        assertBorrowerInLoan(loanId, borrowerId);
        BorrowerAddress a = addresses.findByIdAndOrgId(addressId, org())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Address", addressId));
        apply(a, req.addressLine1(), req.addressLine2(), req.city(), req.state(), req.postalCode(), req.country(),
                req.ownershipType(), req.residencyDurationYears(), req.residencyDurationMonths(),
                req.rentAmount(), req.rentVerified());
        return a;
    }

    @Transactional
    public void delete(UUID loanId, UUID borrowerId, UUID addressId) {
        assertBorrowerInLoan(loanId, borrowerId);
        BorrowerAddress a = addresses.findByIdAndOrgId(addressId, org())
                .filter(x -> x.getBorrowerId().equals(borrowerId))
                .orElseThrow(() -> new NotFoundException("Address", addressId));
        addresses.delete(a);
    }

    private void apply(BorrowerAddress a, String l1, String l2, String city, UsStateCode state,
                       String zip, String country, OwnershipType own,
                       Integer years, Integer months, BigDecimal rent, Boolean rentVerified) {
        if (l1 != null) a.setAddressLine1(l1);
        if (l2 != null) a.setAddressLine2(l2);
        if (city != null) a.setCity(city);
        if (state != null) a.setState(state);
        if (zip != null) a.setPostalCode(zip);
        if (country != null) a.setCountry(country);
        if (own != null) a.setOwnershipType(own);
        if (years != null) a.setResidencyDurationYears(years);
        if (months != null) a.setResidencyDurationMonths(months);
        if (rent != null) a.setRentAmount(rent);
        if (rentVerified != null) a.setRentVerified(rentVerified);
    }
}

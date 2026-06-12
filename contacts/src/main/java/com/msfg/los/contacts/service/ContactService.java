package com.msfg.los.contacts.service;

import com.msfg.los.contacts.domain.Contact;
import com.msfg.los.contacts.repo.ContactRepository;
import com.msfg.los.contacts.web.dto.CreateContactRequest;
import com.msfg.los.contacts.web.dto.UpdateContactRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ContactService {

    private final ContactRepository contacts;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final TenantContext tenantContext;

    public ContactService(ContactRepository contacts,
                          LoanService loanService,
                          LoanAccessGuard accessGuard,
                          TenantContext tenantContext) {
        this.contacts = contacts;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.tenantContext = tenantContext;
    }

    private UUID org() {
        return tenantContext.orgId().orElseThrow(() -> new NotFoundException("Tenant", "current"));
    }

    private Contact load(UUID loanId, UUID contactId) {
        return contacts.findByIdAndOrgId(contactId, org())
                .filter(c -> c.getLoanId().equals(loanId))
                .orElseThrow(() -> new NotFoundException("Contact", contactId));
    }

    // max+1, not count — count reuses ordinals after a delete and collides
    private int nextOrdinal(UUID loanId) {
        return contacts.findTopByLoanIdOrderByOrdinalDesc(loanId)
                .map(c -> c.getOrdinal() + 1)
                .orElse(0);
    }

    @Transactional
    public Contact add(UUID loanId, CreateContactRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        Contact c = new Contact();
        c.setLoanId(loanId);
        c.setRole(req.role());
        c.setName(req.name());
        c.setCompany(req.company());
        c.setPhone(req.phone());
        c.setEmail(req.email());
        c.setOrdinal(nextOrdinal(loanId));
        return contacts.save(c);
    }

    @Transactional(readOnly = true)
    public List<Contact> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return contacts.findByLoanIdOrderByOrdinalAscIdAsc(loanId);
    }

    @Transactional
    public Contact update(UUID loanId, UUID contactId, UpdateContactRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        Contact c = load(loanId, contactId);

        if (req.name() != null && req.name().isBlank())
            throw new ValidationException("name must not be blank");

        if (req.role() != null) c.setRole(req.role());
        if (req.name() != null) c.setName(req.name());
        if (req.company() != null) c.setCompany(req.company());
        if (req.phone() != null) c.setPhone(req.phone());
        if (req.email() != null) c.setEmail(req.email());
        return contacts.save(c);
    }

    @Transactional
    public void delete(UUID loanId, UUID contactId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        contacts.delete(load(loanId, contactId));
    }
}

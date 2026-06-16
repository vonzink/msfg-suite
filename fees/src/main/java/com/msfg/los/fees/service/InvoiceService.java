package com.msfg.los.fees.service;

import com.msfg.los.fees.domain.InvoiceEntry;
import com.msfg.los.fees.repo.InvoiceEntryRepository;
import com.msfg.los.fees.web.dto.UpsertInvoiceRequest;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.platform.error.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class InvoiceService {

    private final InvoiceEntryRepository invoices;
    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;

    public InvoiceService(InvoiceEntryRepository invoices,
                          LoanService loanService,
                          LoanAccessGuard accessGuard) {
        this.invoices = invoices;
        this.loanService = loanService;
        this.accessGuard = accessGuard;
    }

    @Transactional(readOnly = true)
    public List<InvoiceEntry> list(UUID loanId) {
        accessGuard.assertCanAccess(loanService.get(loanId));
        return invoices.findByLoanIdOrderByFeeLabelAsc(loanId);
    }

    @Transactional
    public InvoiceEntry upsert(UUID loanId, UpsertInvoiceRequest req) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        if (req.amountDisclosed() != null && req.amountDisclosed().signum() < 0)
            throw new ValidationException("amountDisclosed must be >= 0");
        if (req.invoiceAmount() != null && req.invoiceAmount().signum() < 0)
            throw new ValidationException("invoiceAmount must be >= 0");
        if (req.borrowerPoc() != null && req.borrowerPoc().signum() < 0)
            throw new ValidationException("borrowerPoc must be >= 0");

        var e = invoices.findByLoanIdAndFeeLabel(loanId, req.feeLabel())
                .orElseGet(() -> {
                    var n = new InvoiceEntry();
                    n.setLoanId(loanId);
                    n.setFeeLabel(req.feeLabel());
                    return n;
                });

        e.setAmountDisclosed(req.amountDisclosed());
        e.setInvoiceAmount(req.invoiceAmount());
        e.setBorrowerPoc(req.borrowerPoc());
        e.setFinalized(req.finalized());
        e.setComment(req.comment());

        return invoices.save(e);
    }
}

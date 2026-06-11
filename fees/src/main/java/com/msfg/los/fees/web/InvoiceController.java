package com.msfg.los.fees.web;

import com.msfg.los.fees.service.InvoiceService;
import com.msfg.los.fees.web.dto.InvoiceEntryResponse;
import com.msfg.los.fees.web.dto.UpsertInvoiceRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/fees/invoices")
public class InvoiceController {

    private final InvoiceService service;

    public InvoiceController(InvoiceService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<InvoiceEntryResponse>> list(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.list(loanId).stream()
                .map(InvoiceEntryResponse::from)
                .toList());
    }

    @PutMapping
    public ApiResponse<InvoiceEntryResponse> upsert(
            @PathVariable UUID loanId,
            @RequestBody @Valid UpsertInvoiceRequest req) {
        return ApiResponse.ok(InvoiceEntryResponse.from(service.upsert(loanId, req)));
    }
}

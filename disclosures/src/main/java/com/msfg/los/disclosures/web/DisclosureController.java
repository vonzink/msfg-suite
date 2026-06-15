package com.msfg.los.disclosures.web;

import com.msfg.los.disclosures.domain.DisclosureKind;
import com.msfg.los.disclosures.service.DisclosureIssuanceService;
import com.msfg.los.disclosures.web.dto.DisclosureResponse;
import com.msfg.los.disclosures.web.dto.IssueDisclosureRequest;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Issuance endpoints for TRID disclosures (Loan Estimate now; Closing Disclosure in Task 10). */
@RestController
public class DisclosureController {

    private final DisclosureIssuanceService service;

    public DisclosureController(DisclosureIssuanceService service) {
        this.service = service;
    }

    @PostMapping("/api/loans/{loanId}/disclosures/loan-estimate")
    public ResponseEntity<ApiResponse<DisclosureResponse>> issueLoanEstimate(
            @PathVariable UUID loanId,
            @RequestBody(required = false) IssueDisclosureRequest req) {
        DisclosureResponse body = DisclosureResponse.from(
                service.issue(loanId, DisclosureKind.LOAN_ESTIMATE, req));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(body));
    }
}

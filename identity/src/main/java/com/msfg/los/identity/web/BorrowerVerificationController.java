package com.msfg.los.identity.web;

import com.msfg.los.identity.service.BorrowerVerificationService;
import com.msfg.los.identity.web.dto.SendVerificationRequest;
import com.msfg.los.identity.web.dto.VerifyCodeRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Staff-initiated borrower verification (security spec §6.2). An LO/Processor/Manager/Admin, scoped to
 * a loan they can access, sends a one-time code to a borrower's suite-stored contact and later verifies
 * it. Both endpoints return <b>204</b> on success and a <b>generic</b> failure otherwise — they never
 * echo the code, the borrower's existence, or whether a contact was present (enumeration-resistant).
 *
 * <p>Authorization is two-layered: a {@code SecurityConfig} filter rule restricts these POSTs to
 * {@code LO/PROCESSOR/MANAGER/ADMIN} (borrower/agent excluded), and the authoritative
 * {@link BorrowerVerificationService} re-checks loan access + tenant + loan membership. Placed OUTSIDE
 * {@code /api/admin/users/**} so it gets its own filter rule.
 */
@RestController
@RequestMapping("/api/identity/borrowers")
public class BorrowerVerificationController {

    private final BorrowerVerificationService service;

    public BorrowerVerificationController(BorrowerVerificationService service) {
        this.service = service;
    }

    @PostMapping("/{borrowerId}/send-verification")
    public ResponseEntity<Void> sendVerification(@PathVariable UUID borrowerId,
                                                 @Valid @RequestBody SendVerificationRequest req) {
        service.send(borrowerId, req.loanId(), req.channel());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{borrowerId}/verify-code")
    public ResponseEntity<Void> verifyCode(@PathVariable UUID borrowerId,
                                           @Valid @RequestBody VerifyCodeRequest req) {
        service.verify(borrowerId, req.loanId(), req.code());
        return ResponseEntity.noContent().build();
    }
}

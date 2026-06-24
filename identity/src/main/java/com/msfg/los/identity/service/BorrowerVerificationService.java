package com.msfg.los.identity.service;

import com.msfg.los.identity.domain.VerificationRequest;
import com.msfg.los.identity.repo.VerificationRequestRepository;
import com.msfg.los.loan.service.LoanAccessGuard;
import com.msfg.los.loan.service.LoanService;
import com.msfg.los.parties.service.BorrowerService;
import com.msfg.los.parties.web.dto.BorrowerContact;
import com.msfg.los.platform.crypto.OtpHasher;
import com.msfg.los.platform.error.TooManyRequestsException;
import com.msfg.los.platform.notify.VerificationChannel;
import com.msfg.los.platform.notify.VerificationCodePort;
import com.msfg.los.platform.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Staff-initiated borrower verification (security spec §6.2/§6.3): an LO/Processor/Manager/Admin,
 * scoped to a loan they can access, sends a one-time code to a borrower's suite-stored contact, and
 * later verifies a code the borrower reports back.
 *
 * <p>Security posture (all enforced here, the authoritative layer; the {@code SecurityConfig} filter
 * rule is defense-in-depth):
 * <ul>
 *   <li><b>Authz:</b> {@code accessGuard.assertCanAccess(loanService.get(loanId))} — the same gate as
 *       {@code BorrowerService.revealSsn} (org-wide for back-office, owner-scoped for LO, 403 for
 *       borrower/agent/platform-admin). Then {@code resolveContact} (tenant + loan scoped) blocks
 *       cross-loan IDOR but returns the SAME generic success for a non-existent borrower (no leak).</li>
 *   <li><b>Code:</b> 6-digit numeric via {@link SecureRandom}; stored ONLY as a salted SHA-256 hash
 *       ({@link OtpHasher}); TTL-bounded; single-use ({@code consumedAt}); constant-time compare;
 *       attempt-capped (locked after {@link #MAX_ATTEMPTS}).</li>
 *   <li><b>Rate-limit:</b> ≤{@link #MAX_SENDS_PER_WINDOW} sends / {@link #SEND_WINDOW} per
 *       {@code (org_id, borrower_id)} AND per acting staff {@code sub}, by counting recent rows.</li>
 *   <li><b>Audit:</b> the {@code verification_request} row IS the record ({@code createdBy} = sub).</li>
 * </ul>
 *
 * <p>All responses are intentionally void/generic — callers translate to HTTP 204, never echoing the
 * code, the borrower's existence, or whether a contact was present.
 */
@Service
public class BorrowerVerificationService {

    private static final Logger log = LoggerFactory.getLogger(BorrowerVerificationService.class);

    private static final SecureRandom RANDOM = new SecureRandom();
    static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_ATTEMPTS = 5;
    static final int MAX_SENDS_PER_WINDOW = 3;
    static final Duration SEND_WINDOW = Duration.ofMinutes(15);

    private final LoanService loanService;
    private final LoanAccessGuard accessGuard;
    private final BorrowerService borrowerService;
    private final VerificationRequestRepository requests;
    private final VerificationAttemptRecorder attemptRecorder;
    private final CurrentUser currentUser;
    private final List<VerificationCodePort> dispatchers;

    public BorrowerVerificationService(LoanService loanService,
                                       LoanAccessGuard accessGuard,
                                       BorrowerService borrowerService,
                                       VerificationRequestRepository requests,
                                       VerificationAttemptRecorder attemptRecorder,
                                       CurrentUser currentUser,
                                       List<VerificationCodePort> dispatchers) {
        this.loanService = loanService;
        this.accessGuard = accessGuard;
        this.borrowerService = borrowerService;
        this.requests = requests;
        this.attemptRecorder = attemptRecorder;
        this.currentUser = currentUser;
        this.dispatchers = dispatchers;
    }

    /**
     * Mint + dispatch a verification code to the borrower. Always completes silently (caller → 204)
     * regardless of borrower/contact existence; throws only on a failed loan-access decision (403) or a
     * rate-limit breach (429).
     */
    @Transactional
    public void send(UUID borrowerId, UUID loanId, VerificationChannel channel) {
        // (1) Loan-access decision — same gate as revealSsn; 403 for non-owner LO / borrower / agent.
        accessGuard.assertCanAccess(loanService.get(loanId));

        // (2) Rate-limit BEFORE touching the borrower — over-limit is a 429 even for a real borrower.
        enforceSendThrottle(borrowerId);

        // (3) Resolve the borrower's contact (tenant + loan scoped). A missing borrower (cross-loan IDOR
        //     or just absent) yields null → generic success, no existence leak.
        BorrowerContact contact = borrowerService.resolveContact(loanId, borrowerId);
        String destination = contact == null ? null : destinationFor(channel, contact);

        // (4) Always persist a row (the audit + throttle key) even when undeliverable — but only attempt
        //     dispatch when we actually have a destination.
        String code = newCode();
        String salt = OtpHasher.newSalt();
        VerificationRequest row = new VerificationRequest();
        row.setLoanId(loanId);
        row.setBorrowerId(borrowerId);
        row.setChannel(channel);
        row.setCodeSalt(salt);
        row.setCodeHash(OtpHasher.hash(code, salt));
        row.setExpiresAt(Instant.now().plus(TTL));
        row.setAttempts(0);
        requests.save(row);

        if (destination != null) {
            VerificationCodePort dispatcher = dispatcherFor(channel);
            if (dispatcher != null) {
                dispatcher.send(channel, destination, code);
            }
            // else: no adapter for this channel (e.g. SMS dormant pre-P6) — generic success, no leak
            //       that the channel is unavailable. The row is still persisted (audit + throttle).
        }
        // else: borrower has no contact on this channel — generic success, nothing dispatched.
    }

    /**
     * Verify a code the borrower reported back. Always completes silently on success (caller → 204);
     * throws {@link com.msfg.los.platform.error.ValidationException} (generic) on any failure (no code,
     * expired, wrong, consumed, locked) so the response is indistinguishable. Throws 403 on a failed
     * loan-access decision.
     */
    @Transactional
    public void verify(UUID borrowerId, UUID loanId, String code) {
        accessGuard.assertCanAccess(loanService.get(loanId));

        VerificationRequest row = requests
                .findFirstByLoanIdAndBorrowerIdOrderByCreatedAtDesc(loanId, borrowerId)
                .orElse(null);

        // Generic failure for every distinguishable reason — never reveal WHY (borrower existence,
        // no outstanding code, expired, locked, wrong code all look the same to the caller).
        if (row == null
                || row.getConsumedAt() != null
                || row.getExpiresAt().isBefore(Instant.now())
                || row.getAttempts() >= MAX_ATTEMPTS) {
            throw genericFailure();
        }

        if (!OtpHasher.matches(code, row.getCodeSalt(), row.getCodeHash())) {
            // Persist the failure in a SEPARATE tx so the increment survives this method's rollback
            // (genericFailure rolls back the outer tx). Lockout kicks in once attempts reaches MAX.
            attemptRecorder.recordFailedAttempt(row.getId());
            throw genericFailure();
        }

        row.setConsumedAt(Instant.now());   // single-use (committed — the success path does NOT roll back)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private void enforceSendThrottle(UUID borrowerId) {
        Instant since = Instant.now().minus(SEND_WINDOW);
        long perBorrower = requests.countByBorrowerIdAndCreatedAtGreaterThanEqual(borrowerId, since);
        long perStaff = requests.countByCreatedByAndCreatedAtGreaterThanEqual(currentSub(), since);
        if (perBorrower >= MAX_SENDS_PER_WINDOW || perStaff >= MAX_SENDS_PER_WINDOW) {
            throw new TooManyRequestsException("Too many verification requests — try again later");
        }
    }

    private String currentSub() {
        // The principal sub (== verification_request.created_by stamped by @CreatedBy). Filter is staff-only,
        // so this is always present for an authorized caller; empty-string guard keeps the count well-defined.
        return currentUser.id().orElse("");
    }

    private static String newCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));   // 000000–999999, SecureRandom
    }

    private static String destinationFor(VerificationChannel channel, BorrowerContact contact) {
        String dest = channel == VerificationChannel.SMS ? contact.cellPhone() : contact.email();
        return (dest == null || dest.isBlank()) ? null : dest;
    }

    /** The adapter for this channel, or {@code null} if none is registered (e.g. SMS dormant pre-P6). */
    private VerificationCodePort dispatcherFor(VerificationChannel channel) {
        return dispatchers.stream()
                .filter(d -> d.supports(channel))
                .findFirst()
                .orElseGet(() -> {
                    log.info("No dispatch adapter for channel {} (likely ops-gated/dormant)", channel);
                    return null;
                });
    }

    private static com.msfg.los.platform.error.ValidationException genericFailure() {
        // Reuse ValidationException (400) for an opaque failure — same envelope, no leak about cause.
        return new com.msfg.los.platform.error.ValidationException("Verification failed");
    }
}

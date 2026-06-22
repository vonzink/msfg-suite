package com.msfg.los.loan.service;

import com.msfg.los.loan.domain.*;
import com.msfg.los.loan.id.SequenceLoanNumberGenerator;
import com.msfg.los.loan.repo.*;
import com.msfg.los.loan.web.dto.*;
import com.msfg.los.platform.error.NotFoundException;
import com.msfg.los.platform.error.ValidationException;
import com.msfg.los.platform.security.CurrentUser;
import com.msfg.los.platform.tenancy.OrgTenantResolver;
import com.msfg.los.platform.tenancy.TenantContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.msfg.los.loan.web.dto.LoanSearchHit;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class LoanService {

    private final LoanRepository loans;
    private final LoanStatusHistoryRepository histories;
    private final SequenceLoanNumberGenerator numberGen;
    private final LoanLifecycle lifecycle;
    private final CurrentUser currentUser;
    private final PrimaryBorrowerNameResolver resolver;
    private final OutstandingConditionResolver conditionResolver;

    public LoanService(LoanRepository loans, LoanStatusHistoryRepository histories,
                       SequenceLoanNumberGenerator numberGen, LoanLifecycle lifecycle,
                       CurrentUser currentUser, PrimaryBorrowerNameResolver resolver,
                       OutstandingConditionResolver conditionResolver) {
        this.loans = loans;
        this.histories = histories;
        this.numberGen = numberGen;
        this.lifecycle = lifecycle;
        this.currentUser = currentUser;
        this.resolver = resolver;
        this.conditionResolver = conditionResolver;
    }

    @Transactional
    public Loan create(CreateLoanRequest req) {
        UUID officer = req.loanOfficerId();
        if (officer == null) {
            officer = currentUser.id().map(s -> {
                try { return UUID.fromString(s); } catch (RuntimeException e) { return null; }
            }).orElse(null);
        }
        if (officer == null) throw new ValidationException("loanOfficerId is required");
        Loan loan = new Loan();
        loan.setLoanNumber(numberGen.next());
        loan.setLoanOfficerId(officer);
        loan.setStatus(LoanStatus.STARTED);
        loan.setLoanPurpose(req.loanPurpose());
        loan.setMortgageType(req.mortgageType());
        loan.setLienPriority(req.lienPriority());
        loan.setAmortizationType(req.amortizationType());
        loan.setNoteAmount(req.noteAmount());
        return loans.save(loan);
    }

    /** Idempotency lookup for the funnel hand-off (Phase A). Tenant-scoped, not-deleted. */
    @Transactional(readOnly = true)
    public Optional<Loan> findBySourceLeadId(String sourceLeadId) {
        if (sourceLeadId == null || sourceLeadId.isBlank()) return Optional.empty();
        return loans.findBySourceLeadIdAndDeletedAtIsNull(sourceLeadId);
    }

    /** Stamp the upstream lead id on a loan (dirty-checked within the tx). 404 if missing/deleted. */
    @Transactional
    public Loan tagSourceLead(UUID loanId, String sourceLeadId) {
        Loan loan = get(loanId);
        loan.setSourceLeadId(sourceLeadId);
        return loan;
    }

    @Transactional(readOnly = true)
    public Loan get(UUID id) {
        // Use findByIdAndOrgId (not findById) so Hibernate generates WHERE id=? AND org_id=?.
        // EntityManager.find() by PK does not apply the @TenantId filter in Hibernate 6 — it
        // only filters JPQL/Criteria. Without the org_id predicate, a cross-tenant caller could
        // load another org's loan and then hit 403 instead of 404 (leaking existence).
        UUID org = TenantContextHolder.get();
        UUID effectiveOrg = org != null ? org : OrgTenantResolver.NIL;
        // deletedAtIsNull: a soft-deleted loan must read as 404, not as a live row (Phase 2 T3).
        return loans.findByIdAndOrgIdAndDeletedAtIsNull(id, effectiveOrg)
                    .orElseThrow(() -> new NotFoundException("Loan", id));
    }

    /** Lookup by human loan number (Phase 2 T3); org-scoped via @TenantId, not-deleted, 404 if missing. */
    @Transactional(readOnly = true)
    public Loan getByNumber(String loanNumber) {
        return loans.findByLoanNumberAndDeletedAtIsNull(loanNumber)
                    .orElseThrow(() -> new NotFoundException("Loan", loanNumber));
    }

    /**
     * Pipeline list with the full filter set (Phase 2 T4), built query-side via {@link LoanSpecifications}
     * (never load-all-then-filter). The caller scope (org-wide vs owning-LO) is the always-on base
     * predicate; each non-null facet adds a SQL predicate. {@code conditionsGt} is resolved through the
     * conditions module's service (port {@link OutstandingConditionResolver}) into a loan-id set, then
     * added as {@code loan.id in (:ids)} — loan-core never touches the loan_condition table.
     *
     * @param filter          bound query parameters (each facet optional)
     * @param sort            resolved, injection-safe sort (default = newest-first; see {@link PipelineSort})
     * @param orgWideView     caller has org-wide view
     * @param callerLoanOfficerId caller's user id (owning LO), used when not org-wide
     * @param page            zero-based page index
     * @param size            page size
     */
    @Transactional(readOnly = true)
    public Page<LoanListItemResponse> pipeline(PipelineFilter filter, Sort sort, boolean orgWideView,
                                               UUID callerLoanOfficerId, int page, int size) {
        Specification<Loan> spec = LoanSpecifications.notDeleted()
                .and(LoanSpecifications.callerScope(orgWideView, callerLoanOfficerId));

        if (filter.statuses() != null && !filter.statuses().isEmpty())
            spec = spec.and(LoanSpecifications.statusIn(filter.statuses()));
        if (filter.lo() != null)
            spec = spec.and(LoanSpecifications.assignedTo(filter.lo()));
        if (filter.loanTypes() != null && !filter.loanTypes().isEmpty())
            spec = spec.and(LoanSpecifications.mortgageTypeIn(filter.loanTypes()));
        if (filter.closingFrom() != null)
            spec = spec.and(LoanSpecifications.consummationOnOrAfter(filter.closingFrom()));
        if (filter.closingTo() != null)
            spec = spec.and(LoanSpecifications.consummationOnOrBefore(filter.closingTo()));
        if (filter.stageAgeGt() != null)
            spec = spec.and(LoanSpecifications.stageOlderThanDays(filter.stageAgeGt()));
        if (filter.amountMin() != null)
            spec = spec.and(LoanSpecifications.amountAtLeast(filter.amountMin()));
        if (filter.amountMax() != null)
            spec = spec.and(LoanSpecifications.amountAtMost(filter.amountMax()));
        if (filter.conditionsGt() != null) {
            // Cross-module via the conditions SERVICE (ArchUnit boundary). Empty set → match nothing.
            Set<UUID> ids = conditionResolver.loanIdsWithOutstandingOver(filter.conditionsGt());
            spec = spec.and(LoanSpecifications.idIn(ids));
        }

        Pageable pageable = PageRequest.of(page, size, sort == null ? PipelineSort.DEFAULT : sort);
        Page<Loan> result = loans.findAll(spec, pageable);
        var names = resolver.primaryBorrowerNamesByLoanIds(result.map(Loan::getId).getContent());
        return result.map(l -> LoanListItemResponse.from(l, names.get(l.getId())));
    }

    /**
     * Pipeline list scoped to an explicit set of loan ids (Phase F T7). Used by {@code /me/loans}
     * for BORROWER and REAL_ESTATE_AGENT callers whose linked loan-ids are resolved by the
     * parties/loan-agent services before this call.
     *
     * <p>Security: an empty id collection short-circuits to an empty page — NEVER falls through to
     * all loans. The id set is tenant-filtered twice: the resolvers' queries are already
     * {@code @TenantId}-filtered, and the pipeline query is also tenant-scoped by Hibernate, so a
     * cross-tenant id (impossible in practice) would produce zero results.
     *
     * @param loanIds explicit set of loan ids to return (empty → empty page)
     * @param sort    resolved sort (default = newest-first; see {@link PipelineSort})
     * @param page    zero-based page index
     * @param size    page size
     */
    @Transactional(readOnly = true)
    public Page<LoanListItemResponse> pipelineByIds(Collection<UUID> loanIds, Sort sort, int page, int size) {
        if (loanIds == null || loanIds.isEmpty()) {
            return Page.empty(PageRequest.of(page, size));
        }
        Specification<Loan> spec = LoanSpecifications.notDeleted()
                .and(LoanSpecifications.idIn(Set.copyOf(loanIds)));
        Pageable pageable = PageRequest.of(page, size, sort == null ? PipelineSort.DEFAULT : sort);
        Page<Loan> result = loans.findAll(spec, pageable);
        var names = resolver.primaryBorrowerNamesByLoanIds(result.map(Loan::getId).getContent());
        return result.map(l -> LoanListItemResponse.from(l, names.get(l.getId())));
    }

    @Transactional
    public Loan update(UUID id, UpdateLoanRequest req) {
        Loan loan = get(id);
        if (req.mortgageType() != null) loan.setMortgageType(req.mortgageType());
        if (req.lienPriority() != null) loan.setLienPriority(req.lienPriority());
        if (req.amortizationType() != null) loan.setAmortizationType(req.amortizationType());
        if (req.noteAmount() != null) loan.setNoteAmount(req.noteAmount());
        // Hibernate returns null for an all-null @Embedded, so a reloaded address-less loan
        // has a null SubjectProperty — instantiate before patching.
        SubjectProperty p = loan.getSubjectProperty();
        if (p == null) { p = new SubjectProperty(); loan.setSubjectProperty(p); }
        if (req.addressLine1() != null) p.setAddressLine1(req.addressLine1());
        if (req.addressLine2() != null) p.setAddressLine2(req.addressLine2());
        if (req.city() != null) p.setCity(req.city());
        if (req.state() != null) p.setState(req.state());
        if (req.postalCode() != null) p.setPostalCode(req.postalCode());
        if (req.estimatedValue() != null) p.setEstimatedValue(req.estimatedValue());

        // §4 Loan Information — apply then validate
        if (req.documentationType() != null) loan.setDocumentationType(req.documentationType());
        if (req.interestRate() != null) loan.setInterestRate(req.interestRate());
        if (req.loanTermMonths() != null) loan.setLoanTermMonths(req.loanTermMonths());
        if (req.baseLoanAmount() != null) loan.setBaseLoanAmount(req.baseLoanAmount());
        if (req.financedFeesAmount() != null) loan.setFinancedFeesAmount(req.financedFeesAmount());
        if (req.secondLoanAmount() != null) loan.setSecondLoanAmount(req.secondLoanAmount());
        if (req.downPaymentAmount() != null) loan.setDownPaymentAmount(req.downPaymentAmount());
        if (req.qualifyingCreditScore() != null) loan.setQualifyingCreditScore(req.qualifyingCreditScore());
        if (req.proposedTaxesMonthly() != null) loan.setProposedTaxesMonthly(req.proposedTaxesMonthly());
        if (req.proposedHazardInsuranceMonthly() != null) loan.setProposedHazardInsuranceMonthly(req.proposedHazardInsuranceMonthly());
        if (req.proposedHoaDuesMonthly() != null) loan.setProposedHoaDuesMonthly(req.proposedHoaDuesMonthly());
        if (req.proposedMortgageInsuranceMonthly() != null) loan.setProposedMortgageInsuranceMonthly(req.proposedMortgageInsuranceMonthly());

        // §4 Subject Property
        if (req.salesPrice() != null) p.setSalesPrice(req.salesPrice());
        if (req.appraisedValue() != null) p.setAppraisedValue(req.appraisedValue());
        if (req.propertyType() != null) p.setPropertyType(req.propertyType());
        if (req.occupancyType() != null) p.setOccupancyType(req.occupancyType());
        if (req.numberOfUnits() != null) p.setNumberOfUnits(req.numberOfUnits());

        if (req.consummationDate() != null) loan.setConsummationDate(req.consummationDate());

        // Validation — each rule its own if/throw
        if (loan.getInterestRate() != null && (loan.getInterestRate().signum() < 0 || loan.getInterestRate().compareTo(java.math.BigDecimal.valueOf(25)) > 0))
            throw new ValidationException("interestRate must be between 0 and 25");
        if (loan.getLoanTermMonths() != null && (loan.getLoanTermMonths() < 1 || loan.getLoanTermMonths() > 480))
            throw new ValidationException("loanTermMonths must be between 1 and 480");
        if (loan.getQualifyingCreditScore() != null && (loan.getQualifyingCreditScore() < 300 || loan.getQualifyingCreditScore() > 850))
            throw new ValidationException("qualifyingCreditScore must be between 300 and 850");
        if (p.getNumberOfUnits() != null && (p.getNumberOfUnits() < 1 || p.getNumberOfUnits() > 4))
            throw new ValidationException("numberOfUnits must be between 1 and 4");
        if (loan.getBaseLoanAmount() != null && loan.getBaseLoanAmount().signum() < 0)
            throw new ValidationException("baseLoanAmount must be >= 0");
        if (loan.getFinancedFeesAmount() != null && loan.getFinancedFeesAmount().signum() < 0)
            throw new ValidationException("financedFeesAmount must be >= 0");
        if (loan.getSecondLoanAmount() != null && loan.getSecondLoanAmount().signum() < 0)
            throw new ValidationException("secondLoanAmount must be >= 0");
        if (loan.getDownPaymentAmount() != null && loan.getDownPaymentAmount().signum() < 0)
            throw new ValidationException("downPaymentAmount must be >= 0");
        if (loan.getProposedTaxesMonthly() != null && loan.getProposedTaxesMonthly().signum() < 0)
            throw new ValidationException("proposedTaxesMonthly must be >= 0");
        if (loan.getProposedHazardInsuranceMonthly() != null && loan.getProposedHazardInsuranceMonthly().signum() < 0)
            throw new ValidationException("proposedHazardInsuranceMonthly must be >= 0");
        if (loan.getProposedHoaDuesMonthly() != null && loan.getProposedHoaDuesMonthly().signum() < 0)
            throw new ValidationException("proposedHoaDuesMonthly must be >= 0");
        if (loan.getProposedMortgageInsuranceMonthly() != null && loan.getProposedMortgageInsuranceMonthly().signum() < 0)
            throw new ValidationException("proposedMortgageInsuranceMonthly must be >= 0");
        if (p.getSalesPrice() != null && p.getSalesPrice().signum() < 0)
            throw new ValidationException("salesPrice must be >= 0");
        if (p.getAppraisedValue() != null && p.getAppraisedValue().signum() < 0)
            throw new ValidationException("appraisedValue must be >= 0");

        return loan;
    }

    @Transactional
    public Loan transition(UUID id, TransitionRequest req, Set<String> authorities) {
        Loan loan = get(id);
        LoanStatus from = loan.getStatus();
        lifecycle.assertTransition(from, req.targetStatus(), authorities);
        // Backdating (Phase 2 T3): use the supplied effective time, else now. When null, behavior is
        // unchanged from before — the row's transitioned_at and the loan mirror both get now().
        Instant effective = req.transitionedAt() != null ? req.transitionedAt() : Instant.now();
        loan.setStatus(req.targetStatus());
        loan.setStatusChangedAt(effective);
        LoanStatusHistory h = new LoanStatusHistory();
        h.setLoanId(loan.getId());
        h.setFromStatus(from);
        h.setToStatus(req.targetStatus());
        h.setReason(req.reason());
        h.setTransitionedAt(effective);
        // Actor is captured automatically on the history row via AuditableEntity.createdBy (JPA auditing).
        histories.save(h);
        return loan;
    }

    /**
     * Soft-delete (Phase 2 T3): stamp deleted_at. Idempotent over a missing/already-deleted loan —
     * both surface as 404 (get() already filters deleted, so a second call cannot find the row).
     */
    @Transactional
    public Loan softDelete(UUID id) {
        Loan loan = get(id);   // 404 if missing OR already soft-deleted
        loan.setDeletedAt(Instant.now());
        return loan;
    }

    /**
     * Typeahead search (Phase 2 T3). Ranking: (1) exact loanNumber, (2) loanNumber prefix,
     * (3) borrower-name substring. Not-deleted; scoped to the caller (org-wide → whole org,
     * else own loans by loanOfficerId). Borrower-name resolution is batched (no N+1).
     *
     * @param q        raw query (min length 2 enforced by the controller)
     * @param limit    capped (1..50) by the controller
     * @param orgWide  caller has org-wide view
     * @param meId     caller's user id (the owning LO), required when not org-wide
     */
    @Transactional(readOnly = true)
    public List<LoanSearchHit> search(String q, int limit, boolean orgWide, UUID meId) {
        String norm = q == null ? "" : q.trim();
        if (norm.length() < 2) return List.of();
        String lower = norm.toLowerCase();
        String like = "%" + lower + "%";

        // Preserve insertion order (rank order) and de-dup by loan id.
        Map<UUID, Loan> ordered = new LinkedHashMap<>();

        // Leg 1+2: loanNumber matches (exact + prefix + contains), then sorted exact→prefix→other.
        List<Loan> byNumber = orgWide
                ? loans.searchByLoanNumberLikeOrgWide(like)
                : loans.searchByLoanNumberLikeForOfficer(meId, like);
        byNumber.sort(Comparator
                .comparingInt((Loan l) -> numberRank(l.getLoanNumber(), lower))
                .thenComparing(Loan::getLoanNumber));
        for (Loan l : byNumber) ordered.putIfAbsent(l.getId(), l);

        // Leg 3: borrower-name substring (via the cross-module port). Intersect with caller scope.
        Set<UUID> nameLoanIds = resolver.loanIdsByPrimaryBorrowerNameLike(norm);
        if (!nameLoanIds.isEmpty()) {
            List<Loan> byName = orgWide
                    ? loans.findActiveByIds(nameLoanIds)
                    : loans.findActiveByIdsForOfficer(meId, nameLoanIds);
            byName.sort(Comparator.comparing(Loan::getLoanNumber));
            for (Loan l : byName) ordered.putIfAbsent(l.getId(), l);
        }

        List<Loan> hits = new ArrayList<>(ordered.values());
        if (hits.size() > limit) hits = hits.subList(0, limit);

        // Batched borrower-name resolution (no N+1) for the final hit set.
        Map<UUID, String> names = resolver.primaryBorrowerNamesByLoanIds(
                hits.stream().map(Loan::getId).toList());
        List<LoanSearchHit> out = new ArrayList<>(hits.size());
        for (Loan l : hits) {
            var sp = l.getSubjectProperty();
            out.add(new LoanSearchHit(
                    l.getId(), l.getLoanNumber(), names.get(l.getId()),
                    sp != null ? sp.getCity() : null,
                    sp != null ? sp.getState() : null,
                    l.getStatus()));
        }
        return out;
    }

    /** 0 = exact loanNumber, 1 = prefix, 2 = other (contains). Lower-cased compare. */
    private static int numberRank(String loanNumber, String lowerQuery) {
        if (loanNumber == null) return 2;
        String ln = loanNumber.toLowerCase();
        if (ln.equals(lowerQuery)) return 0;
        if (ln.startsWith(lowerQuery)) return 1;
        return 2;
    }

    @Transactional(readOnly = true)
    public List<LoanStatusHistory> history(UUID loanId) {
        return histories.findByLoanIdOrderByCreatedAtAsc(loanId);
    }
}

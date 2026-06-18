package com.msfg.los.documents.service;

import com.msfg.los.documents.domain.DocumentStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure document-status transition table (mirror of pricing's {@code LockTransitions}).
 *
 * <p>No Spring, no DB — just the verbatim VALID_TRANSITIONS edge set from the Phase-1 spec
 * (the mortgage-app source state machine). The {@link DocumentStatusService} layer owns persistence,
 * review-field side effects and the append-only status_history; this class owns only the
 * <em>legality</em> of an edge.
 *
 * <p>Verbatim edges (10 states):
 * <pre>
 * PENDING_UPLOAD        → {UPLOADED, SCAN_FAILED}
 * UPLOADED              → {SCAN_PENDING, READY_FOR_REVIEW, DELETED_SOFT}
 * SCAN_PENDING          → {SCAN_FAILED, READY_FOR_REVIEW}
 * SCAN_FAILED           → {READY_FOR_REVIEW, DELETED_SOFT}
 * READY_FOR_REVIEW      → {ACCEPTED, REJECTED, NEEDS_BORROWER_ACTION, ARCHIVED, DELETED_SOFT}
 * NEEDS_BORROWER_ACTION → {UPLOADED, DELETED_SOFT}
 * ACCEPTED              → {ARCHIVED, READY_FOR_REVIEW}
 * REJECTED              → {READY_FOR_REVIEW, DELETED_SOFT}
 * ARCHIVED              → {READY_FOR_REVIEW}
 * DELETED_SOFT          → {} (terminal)
 * </pre>
 */
public final class DocumentStatusTransitions {

    private DocumentStatusTransitions() {}

    /** current → set of allowed targets. Immutable. */
    private static final Map<DocumentStatus, Set<DocumentStatus>> VALID_TRANSITIONS;

    static {
        Map<DocumentStatus, Set<DocumentStatus>> m = new EnumMap<>(DocumentStatus.class);
        m.put(DocumentStatus.PENDING_UPLOAD,
                EnumSet.of(DocumentStatus.UPLOADED, DocumentStatus.SCAN_FAILED));
        m.put(DocumentStatus.UPLOADED,
                EnumSet.of(DocumentStatus.SCAN_PENDING, DocumentStatus.READY_FOR_REVIEW, DocumentStatus.DELETED_SOFT));
        m.put(DocumentStatus.SCAN_PENDING,
                EnumSet.of(DocumentStatus.SCAN_FAILED, DocumentStatus.READY_FOR_REVIEW));
        m.put(DocumentStatus.SCAN_FAILED,
                EnumSet.of(DocumentStatus.READY_FOR_REVIEW, DocumentStatus.DELETED_SOFT));
        m.put(DocumentStatus.READY_FOR_REVIEW,
                EnumSet.of(DocumentStatus.ACCEPTED, DocumentStatus.REJECTED, DocumentStatus.NEEDS_BORROWER_ACTION,
                        DocumentStatus.ARCHIVED, DocumentStatus.DELETED_SOFT));
        m.put(DocumentStatus.NEEDS_BORROWER_ACTION,
                EnumSet.of(DocumentStatus.UPLOADED, DocumentStatus.DELETED_SOFT));
        m.put(DocumentStatus.ACCEPTED,
                EnumSet.of(DocumentStatus.ARCHIVED, DocumentStatus.READY_FOR_REVIEW));
        m.put(DocumentStatus.REJECTED,
                EnumSet.of(DocumentStatus.READY_FOR_REVIEW, DocumentStatus.DELETED_SOFT));
        m.put(DocumentStatus.ARCHIVED,
                EnumSet.of(DocumentStatus.READY_FOR_REVIEW));
        m.put(DocumentStatus.DELETED_SOFT,
                EnumSet.noneOf(DocumentStatus.class)); // terminal

        VALID_TRANSITIONS = m;
    }

    /** The allowed target states from {@code current} (empty for terminal states). Never null. */
    public static Set<DocumentStatus> validTransitions(DocumentStatus current) {
        return EnumSet.copyOf(orEmpty(current));
    }

    /** True iff {@code current → target} is a legal edge. */
    public static boolean isAllowed(DocumentStatus current, DocumentStatus target) {
        return target != null && orEmpty(current).contains(target);
    }

    /**
     * Assert that {@code current → target} is legal, throwing {@link DocumentStatusConflictException}
     * (HTTP 409, code DOCUMENT_STATUS_CONFLICT) with a message listing the valid transitions otherwise.
     */
    public static void assertAllowed(DocumentStatus current, DocumentStatus target) {
        if (isAllowed(current, target)) {
            return;
        }
        throw new DocumentStatusConflictException(
                "Cannot transition document from " + current + " to " + target
                        + ". Valid transitions: " + describe(current));
    }

    private static Set<DocumentStatus> orEmpty(DocumentStatus current) {
        return VALID_TRANSITIONS.getOrDefault(current, EnumSet.noneOf(DocumentStatus.class));
    }

    /** Human-readable valid-target list (e.g. "ACCEPTED, REJECTED" or "none (terminal)"). */
    private static String describe(DocumentStatus current) {
        Set<DocumentStatus> targets = orEmpty(current);
        if (targets.isEmpty()) {
            return "none (terminal)";
        }
        return targets.stream().map(Enum::name).collect(Collectors.joining(", "));
    }
}

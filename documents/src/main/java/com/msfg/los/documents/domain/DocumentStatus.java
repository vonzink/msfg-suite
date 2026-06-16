package com.msfg.los.documents.domain;

/**
 * Document lifecycle states (parity with mortgage-app source).
 * Virus-scan states are kept as an unwired seam (confirm currently sets UPLOADED).
 * NEEDS_BORROWER_ACTION is retained as an internal "needs revision" marker (staff-only).
 */
public enum DocumentStatus {
    PENDING_UPLOAD,
    UPLOADED,
    SCAN_PENDING,
    SCAN_FAILED,
    READY_FOR_REVIEW,
    NEEDS_BORROWER_ACTION,
    ACCEPTED,
    REJECTED,
    ARCHIVED,
    DELETED_SOFT
}

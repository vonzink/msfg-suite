package com.msfg.los.documents.web.dto;

/**
 * Review-action body for {@code accept} / {@code reject} / {@code request-revision}.
 *
 * <p>{@code notes} is OPTIONAL for accept, but REQUIRED (non-blank, enforced in the service so the
 * 400 carries a descriptive message) for reject and request-revision. The body itself is optional on
 * accept (no body → null notes).
 *
 * @param notes reviewer notes (recorded on the document + status history)
 */
public record ReviewRequest(
        String notes) {
}

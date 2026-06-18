package com.msfg.los.documents.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.msfg.los.documents.domain.DocumentStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pure-logic unit tests for the document-status transition table (mirror of {@code LockTransitionsTest}).
 *
 * <p>{@link #fullMatrix} drives the FULL 10×10 (current × target) matrix — 100 cells — asserting that
 * every cell in {@link #EXPECTED} (the spec edge set, re-stated INDEPENDENTLY here) passes
 * {@code assertAllowed}, and every other cell throws {@link DocumentStatusConflictException}.
 *
 * <p>TEETH CHECK: {@link #EXPECTED} is an independent transcription of the spec — it is NOT derived
 * from {@code DocumentStatusTransitions}. If a production edge were flipped (added or dropped), the
 * matched cell would disagree with {@code isAllowed}/{@code assertAllowed} and this test would FAIL.
 * Verified locally by temporarily adding {@code ACCEPTED} to {@code UPLOADED}'s set (one cell turned
 * red) and by dropping {@code ACCEPTED} from {@code READY_FOR_REVIEW} (one cell turned red).
 */
class DocumentStatusTransitionsTest {

    /** Independent re-statement of the spec edge set (the test's source of truth). */
    private static final Map<DocumentStatus, Set<DocumentStatus>> EXPECTED = buildExpected();

    private static Map<DocumentStatus, Set<DocumentStatus>> buildExpected() {
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
                EnumSet.noneOf(DocumentStatus.class));
        return m;
    }

    static Stream<Arguments> allCells() {
        Stream.Builder<Arguments> b = Stream.builder();
        for (DocumentStatus from : DocumentStatus.values()) {
            for (DocumentStatus to : DocumentStatus.values()) {
                boolean allowed = EXPECTED.getOrDefault(from, EnumSet.noneOf(DocumentStatus.class)).contains(to);
                b.add(Arguments.of(from, to, allowed));
            }
        }
        return b.build();
    }

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @MethodSource("allCells")
    void fullMatrix(DocumentStatus from, DocumentStatus to, boolean allowed) {
        assertThat(DocumentStatusTransitions.isAllowed(from, to)).isEqualTo(allowed);
        if (allowed) {
            assertThatCode(() -> DocumentStatusTransitions.assertAllowed(from, to))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> DocumentStatusTransitions.assertAllowed(from, to))
                    .isInstanceOf(DocumentStatusConflictException.class);
        }
    }

    @Test
    void deletedSoftIsTerminal_noOutEdges() {
        assertThat(DocumentStatusTransitions.validTransitions(DocumentStatus.DELETED_SOFT)).isEmpty();
        for (DocumentStatus to : DocumentStatus.values()) {
            assertThat(DocumentStatusTransitions.isAllowed(DocumentStatus.DELETED_SOFT, to)).isFalse();
        }
    }

    @Test
    void validTransitionsReturnsTheExactAllowedSet() {
        assertThat(DocumentStatusTransitions.validTransitions(DocumentStatus.READY_FOR_REVIEW))
                .containsExactlyInAnyOrder(
                        DocumentStatus.ACCEPTED, DocumentStatus.REJECTED, DocumentStatus.NEEDS_BORROWER_ACTION,
                        DocumentStatus.ARCHIVED, DocumentStatus.DELETED_SOFT);
        assertThat(DocumentStatusTransitions.validTransitions(DocumentStatus.UPLOADED))
                .containsExactlyInAnyOrder(
                        DocumentStatus.SCAN_PENDING, DocumentStatus.READY_FOR_REVIEW, DocumentStatus.DELETED_SOFT);
    }

    @Test
    void validTransitionsIsDefensiveCopy() {
        Set<DocumentStatus> t = DocumentStatusTransitions.validTransitions(DocumentStatus.UPLOADED);
        t.clear(); // mutating the returned set must not corrupt the table
        assertThat(DocumentStatusTransitions.isAllowed(DocumentStatus.UPLOADED, DocumentStatus.READY_FOR_REVIEW))
                .isTrue();
    }

    @Test
    void nullTargetIsNeverAllowed() {
        assertThat(DocumentStatusTransitions.isAllowed(DocumentStatus.READY_FOR_REVIEW, null)).isFalse();
    }

    // ── message + HTTP code (these surface as the 409 body) ──────────────────────────────────

    @Test
    void illegalTransitionMessageListsValidTransitions() {
        assertThatThrownBy(() ->
                DocumentStatusTransitions.assertAllowed(DocumentStatus.UPLOADED, DocumentStatus.ACCEPTED))
                .isInstanceOf(DocumentStatusConflictException.class)
                .hasMessageContaining("Cannot transition document from UPLOADED to ACCEPTED")
                .hasMessageContaining("Valid transitions:")
                .hasMessageContaining("READY_FOR_REVIEW");
    }

    @Test
    void terminalTransitionMessageSaysNone() {
        assertThatThrownBy(() ->
                DocumentStatusTransitions.assertAllowed(DocumentStatus.DELETED_SOFT, DocumentStatus.READY_FOR_REVIEW))
                .isInstanceOf(DocumentStatusConflictException.class)
                .hasMessageContaining("none (terminal)");
    }

    @Test
    void conflictIsAlways409WithDocumentStatusConflictCode() {
        try {
            DocumentStatusTransitions.assertAllowed(DocumentStatus.UPLOADED, DocumentStatus.ACCEPTED);
        } catch (DocumentStatusConflictException e) {
            assertThat(e.status().value()).isEqualTo(409);
            assertThat(e.code()).isEqualTo("DOCUMENT_STATUS_CONFLICT");
        }
    }
}

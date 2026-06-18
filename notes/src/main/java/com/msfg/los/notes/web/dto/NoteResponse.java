package com.msfg.los.notes.web.dto;

import com.msfg.los.notes.domain.LoanNote;

import java.time.Instant;
import java.util.UUID;

public record NoteResponse(
        UUID id,
        UUID loanId,
        String authorId,
        String authorName,
        String content,
        Instant createdAt,
        Instant updatedAt) {

    public static NoteResponse from(LoanNote n) {
        return new NoteResponse(
                n.getId(),
                n.getLoanId(),
                n.getAuthorId(),
                n.getAuthorName(),
                n.getContent(),
                n.getCreatedAt(),
                n.getUpdatedAt());
    }
}

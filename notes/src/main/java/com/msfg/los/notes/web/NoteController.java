package com.msfg.los.notes.web;

import com.msfg.los.notes.service.NoteService;
import com.msfg.los.notes.web.dto.CreateNoteRequest;
import com.msfg.los.notes.web.dto.NoteListResponse;
import com.msfg.los.notes.web.dto.NoteResponse;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Loan-scoped note endpoints. Staff-only, loan-access-gated (no role gate beyond loan access,
 * matching mortgage-app) — enforcement is in {@link NoteService} via the loan guard.
 */
@RestController
@RequestMapping("/api/loans/{loanId}/notes")
public class NoteController {

    private final NoteService service;

    public NoteController(NoteService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<NoteListResponse> list(@PathVariable UUID loanId) {
        var rows = service.list(loanId).stream().map(NoteResponse::from).toList();
        return ApiResponse.ok(NoteListResponse.of(rows));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<NoteResponse>> create(
            @PathVariable UUID loanId,
            @Valid @RequestBody CreateNoteRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(NoteResponse.from(service.create(loanId, req))));
    }

    @DeleteMapping("/{noteId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID noteId) {
        service.delete(loanId, noteId);
        return ResponseEntity.noContent().build();
    }
}

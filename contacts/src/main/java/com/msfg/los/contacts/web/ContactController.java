package com.msfg.los.contacts.web;

import com.msfg.los.contacts.service.ContactService;
import com.msfg.los.contacts.web.dto.ContactResponse;
import com.msfg.los.contacts.web.dto.CreateContactRequest;
import com.msfg.los.contacts.web.dto.UpdateContactRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/contacts")
public class ContactController {

    private final ContactService service;

    public ContactController(ContactService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ContactResponse>> add(
            @PathVariable UUID loanId,
            @Valid @RequestBody CreateContactRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ContactResponse.from(service.add(loanId, req))));
    }

    @GetMapping
    public ApiResponse<List<ContactResponse>> list(@PathVariable UUID loanId) {
        return ApiResponse.ok(service.list(loanId).stream().map(ContactResponse::from).toList());
    }

    @PatchMapping("/{contactId}")
    public ApiResponse<ContactResponse> update(
            @PathVariable UUID loanId,
            @PathVariable UUID contactId,
            @Valid @RequestBody UpdateContactRequest req) {
        return ApiResponse.ok(ContactResponse.from(service.update(loanId, contactId, req)));
    }

    @DeleteMapping("/{contactId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID contactId) {
        service.delete(loanId, contactId);
        return ResponseEntity.noContent().build();
    }
}

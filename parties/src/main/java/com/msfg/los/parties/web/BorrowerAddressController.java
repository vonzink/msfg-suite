package com.msfg.los.parties.web;

import com.msfg.los.parties.service.BorrowerAddressService;
import com.msfg.los.parties.web.dto.AddAddressRequest;
import com.msfg.los.parties.web.dto.AddressResponse;
import com.msfg.los.parties.web.dto.UpdateAddressRequest;
import com.msfg.los.platform.web.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/loans/{loanId}/borrowers/{borrowerId}/addresses")
public class BorrowerAddressController {

    private final BorrowerAddressService service;

    public BorrowerAddressController(BorrowerAddressService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AddressResponse>> add(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @Valid @RequestBody AddAddressRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(AddressResponse.from(service.add(loanId, borrowerId, req))));
    }

    @GetMapping
    public ApiResponse<List<AddressResponse>> list(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId) {
        return ApiResponse.ok(service.list(loanId, borrowerId).stream().map(AddressResponse::from).toList());
    }

    @PatchMapping("/{addressId}")
    public ApiResponse<AddressResponse> update(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @PathVariable UUID addressId,
            @Valid @RequestBody UpdateAddressRequest req) {
        return ApiResponse.ok(AddressResponse.from(service.update(loanId, borrowerId, addressId, req)));
    }

    @DeleteMapping("/{addressId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID loanId,
            @PathVariable UUID borrowerId,
            @PathVariable UUID addressId) {
        service.delete(loanId, borrowerId, addressId);
        return ResponseEntity.noContent().build();
    }
}

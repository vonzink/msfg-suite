package com.msfg.los.tenancy.web;
import com.msfg.los.platform.web.ApiResponse;
import com.msfg.los.platform.web.PagedResponse;
import com.msfg.los.tenancy.domain.Organization;
import com.msfg.los.tenancy.service.OrganizationService;
import com.msfg.los.tenancy.web.dto.*;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/organizations")
public class OrganizationController {
    private final OrganizationService service;
    public OrganizationController(OrganizationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<OrgResponse>> create(@Valid @RequestBody CreateOrgRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(OrgResponse.from(service.create(req))));
    }
    @GetMapping
    public ApiResponse<PagedResponse<OrgResponse>> list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Page<Organization> result = service.list(PageRequest.of(page, size));
        return ApiResponse.ok(PagedResponse.from(result.map(OrgResponse::from)));
    }
    @GetMapping("/{id}")
    public ApiResponse<OrgResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(OrgResponse.from(service.get(id)));
    }
    @PatchMapping("/{id}")
    public ApiResponse<OrgResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateOrgRequest req) {
        return ApiResponse.ok(OrgResponse.from(service.update(id, req)));
    }
}

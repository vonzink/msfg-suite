package com.msfg.los.identity.web;

import com.msfg.los.identity.domain.UserAccount;
import com.msfg.los.identity.service.UserAdminService;
import com.msfg.los.identity.web.dto.CreateUserRequest;
import com.msfg.los.identity.web.dto.UserSummary;
import com.msfg.los.platform.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * LO/Admin user administration — create user + reset password. Role-gated to LO + ADMIN at the
 * filter ({@code SecurityConfig} {@code /api/admin/users/**}); the new user is scoped to the acting
 * admin's org in {@link UserAdminService}.
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<UserSummary>> create(@RequestBody CreateUserRequest req) {
        UserAccount u = service.createUser(req.email(), req.name(), req.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(UserSummary.from(u)));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable UUID id) {
        service.resetPassword(id);
        return ApiResponse.ok(null);
    }
}

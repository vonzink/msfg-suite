package com.msfg.los.platform.security;

/**
 * Write-side identity-provider seam: create users and trigger password resets. The provider-neutral
 * counterpart to {@link PrincipalPort} (the read side). The OIDC/Cognito implementation lives in an
 * adapter ({@code CognitoUserAdminAdapter}); a stub adapter backs local/test. One method per
 * external action — a non-Cognito IdP just supplies a different adapter.
 */
public interface UserAdminPort {

    /** Create a user in the identity provider; returns the provider subject id (e.g. Cognito {@code sub}). */
    String createUser(NewUser user);

    /** Trigger a password reset for the identity-provider user with the given subject id. */
    void resetPassword(String subjectId);

    /** A user to create. {@code role} is a {@link Role} enum name (e.g. {@code "BORROWER"}). */
    record NewUser(String email, String name, String role) {}
}

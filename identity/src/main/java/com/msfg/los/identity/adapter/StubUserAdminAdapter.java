package com.msfg.los.identity.adapter;

import com.msfg.los.platform.security.UserAdminPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Local/test {@link UserAdminPort}: mints a synthetic subject id and no-ops the reset, so the
 * user-admin flow is fully exercisable without a real Cognito pool. The real
 * {@code CognitoUserAdminAdapter} supersedes this once Cognito is wired (config-gated). Never logs
 * the email (PII) — role + sub only.
 */
@Component
public class StubUserAdminAdapter implements UserAdminPort {

    private static final Logger log = LoggerFactory.getLogger(StubUserAdminAdapter.class);

    @Override
    public String createUser(NewUser user) {
        String sub = UUID.randomUUID().toString();
        log.info("[stub user-admin] createUser role={} -> sub={}", user.role(), sub);
        return sub;
    }

    @Override
    public void resetPassword(String subjectId) {
        log.info("[stub user-admin] resetPassword sub={}", subjectId);
    }
}

package com.msfg.los.platform.tenancy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class OrgTenantResolverTest {
    private final OrgTenantResolver resolver = new OrgTenantResolver();
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test void returnsCurrentOrgWhenSet() {
        UUID org = UUID.randomUUID();
        TenantContextHolder.set(org);
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(org);
    }
    @Test void returnsNilSentinelWhenUnset_failClosed() {
        assertThat(resolver.resolveCurrentTenantIdentifier()).isEqualTo(new UUID(0, 0));
    }
}

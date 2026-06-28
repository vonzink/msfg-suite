package com.msfg.los.platform.tenancy;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Wraps the application's auto-configured {@link DataSource} bean in a {@link GucConnectionDataSource}
 * so every pooled connection is tenant-stamped at acquisition. Done as a {@link BeanPostProcessor}
 * (rather than replacing the bean) so Spring Boot's Hikari auto-configuration, {@code @ConfigurationProperties}
 * binding and actuator metrics all apply to the underlying DataSource unchanged — we only decorate
 * the connection lifecycle.
 *
 * <p>Flyway and the (optional) {@code RlsRuntimeRoleVerifier} both run through this wrapped DataSource;
 * during startup there is no tenant context, so the GUC is stamped empty (harmless — Flyway runs as
 * the owner/superuser and is not subject to RLS).
 *
 * <p>If the documented owner-vs-runtime split is later configured as a SEPARATE owner DataSource bean
 * (Flyway = owner, runtime = app_user), this post-processor wraps both. That is harmless: the owner
 * connection bypasses RLS, so stamping {@code app.current_org} on it has no effect.
 */
@Component
public class GucDataSourceWrapperBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof DataSource ds && !(bean instanceof GucConnectionDataSource)) {
            return new GucConnectionDataSource(ds);
        }
        return bean;
    }
}

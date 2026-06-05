package com.msfg.los.platform.tenancy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Aspect @Component @Order(100)
public class TenantRlsAspect {
    @PersistenceContext private EntityManager em;

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)"
          + " || @within(org.springframework.transaction.annotation.Transactional)")
    public Object setCurrentOrg(ProceedingJoinPoint pjp) throws Throwable {
        UUID org = TenantContextHolder.get();
        if (org != null) {
            // Sets the transaction-local GUC the RLS policy reads. getResultList() (ignored) over
            // getSingleResult() — set_config always returns one row, but this avoids any
            // NoResultException risk on a path that runs for every transaction.
            em.createNativeQuery("select set_config('app.current_org', :org, true)")
              .setParameter("org", org.toString())
              .getResultList();
        }
        return pjp.proceed();
    }
}

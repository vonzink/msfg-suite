package com.msfg.los.loan.id;

import com.msfg.los.platform.id.LoanNumberGenerator;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

@Component
public class SequenceLoanNumberGenerator implements LoanNumberGenerator {

    private final EntityManager em;

    public SequenceLoanNumberGenerator(EntityManager em) {
        this.em = em;
    }

    @Override
    public String format(long sequenceValue) {
        return String.format("1%09d", sequenceValue);
    }

    // `format` is the substitutable contract on LoanNumberGenerator (pure, unit-testable).
    // `next()` is intentionally NOT on the interface: it is this implementation's stateful op,
    // pulling the next value from the DB sequence (concurrency-safe). Callers needing a fresh
    // number depend on this concrete generator.
    public String next() {
        Number val = (Number) em.createNativeQuery("select nextval('loan_number_seq')").getSingleResult();
        return format(val.longValue());
    }
}

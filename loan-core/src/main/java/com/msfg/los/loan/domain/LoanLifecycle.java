package com.msfg.los.loan.domain;

import com.msfg.los.platform.error.ConflictException;
import com.msfg.los.platform.error.ForbiddenException;
import com.msfg.los.platform.security.Role;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static com.msfg.los.loan.domain.LoanStatus.*;

@Component
public class LoanLifecycle {

    // TODO Spec-2: SUSPENDED is a non-terminal dead-end here — it can only be WITHDRAWN/CANCELLED,
    // there is no SUSPENDED -> IN_UNDERWRITING resume edge yet. Add the resume path when underwriting
    // workflow lands. Similarly, CLOSING is currently ungated (consider requiring CLOSER).
    private static final Map<LoanStatus, Set<LoanStatus>> FORWARD = Map.of(
        STARTED, Set.of(APPLICATION_IN_PROGRESS),
        APPLICATION_IN_PROGRESS, Set.of(SUBMITTED),
        SUBMITTED, Set.of(IN_UNDERWRITING),
        IN_UNDERWRITING, Set.of(APPROVED_WITH_CONDITIONS, DENIED, SUSPENDED),
        APPROVED_WITH_CONDITIONS, Set.of(CLEAR_TO_CLOSE),
        CLEAR_TO_CLOSE, Set.of(CLOSING),
        CLOSING, Set.of(FUNDED)
    );

    private static final Set<LoanStatus> CANCELLABLE = Set.of(WITHDRAWN, CANCELLED);

    private static final Map<LoanStatus, Role> ENTRY_ROLE = Map.of(
        APPROVED_WITH_CONDITIONS, Role.UNDERWRITER,
        DENIED, Role.UNDERWRITER,
        SUSPENDED, Role.UNDERWRITER,
        CLEAR_TO_CLOSE, Role.UNDERWRITER,
        FUNDED, Role.CLOSER
    );

    public List<LoanStatus> allowedTransitions(LoanStatus from, Set<String> authorities) {
        List<LoanStatus> out = new ArrayList<>();
        FORWARD.getOrDefault(from, Set.of()).stream()
            .sorted(Comparator.comparingInt(Enum::ordinal))
            .forEach(out::add);
        if (!from.isTerminal()) { out.add(LoanStatus.WITHDRAWN); out.add(LoanStatus.CANCELLED); }
        return out.stream().filter(to -> {
            Role required = ENTRY_ROLE.get(to);
            return required == null || authorities.contains(required.authority()) || authorities.contains(Role.ADMIN.authority());
        }).toList();
    }

    public void assertTransition(LoanStatus from, LoanStatus to, Set<String> authorities) {
        if (from == to) throw new ConflictException("Loan already in status " + to);
        boolean legal = FORWARD.getOrDefault(from, Set.of()).contains(to)
            || (!from.isTerminal() && CANCELLABLE.contains(to));
        if (!legal) throw new ConflictException("Illegal transition " + from + " -> " + to);
        Role required = ENTRY_ROLE.get(to);
        if (required != null
            && !authorities.contains(required.authority())
            && !authorities.contains(Role.ADMIN.authority())) {
            throw new ForbiddenException("Transition to " + to + " requires role " + required);
        }
    }
}

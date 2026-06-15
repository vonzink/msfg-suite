package com.msfg.los.disclosures.service;

import com.msfg.los.disclosures.domain.BusinessDayType;
import com.msfg.los.disclosures.timing.BusinessDayCalculator;
import com.msfg.los.disclosures.timing.GeneralBusinessDayConfig;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * TRID disclosure-timing deadlines, expressed in the two regulatory business-day conventions of
 * 12 CFR 1026.2(a)(6) (see {@link BusinessDayCalculator}).
 *
 * <ul>
 *   <li>LE delivery deadline — no later than 3 <em>general</em> business days after application
 *       (1026.19(e)(1)(iii)).</li>
 *   <li>Constructive receipt — mailed/delivered disclosures are deemed received 3 <em>precise</em>
 *       business days after delivery (1026.19(e)(1)(iv) mailbox rule).</li>
 *   <li>Earliest consummation after an LE — 7 <em>precise</em> business days after delivery
 *       (1026.19(e)(1)(iii)(B) waiting period).</li>
 *   <li>Earliest consummation after a CD — 3 <em>precise</em> business days after the consumer
 *       receives the CD (1026.19(f)(1)(ii)).</li>
 * </ul>
 */
@Service
public class DisclosureTimingService {

    private final BusinessDayCalculator calculator;

    public DisclosureTimingService(BusinessDayCalculator calculator) {
        this.calculator = calculator;
    }

    /** 3 general business days after application — the LE must be delivered/placed in the mail by this date. */
    public LocalDate leDeliveryDeadline(LocalDate applicationDate) {
        return calculator.addBusinessDays(
                applicationDate, 3, BusinessDayType.GENERAL, GeneralBusinessDayConfig.DEFAULT);
    }

    /** Mailbox rule: disclosures delivered by mail are deemed received 3 precise business days later. */
    public LocalDate constructiveReceived(LocalDate deliveredDate) {
        return calculator.addBusinessDays(
                deliveredDate, 3, BusinessDayType.PRECISE, GeneralBusinessDayConfig.DEFAULT);
    }

    /** Earliest a loan may consummate after the LE is delivered — 7 precise business days. */
    public LocalDate earliestConsummationForLe(LocalDate deliveredDate) {
        return calculator.addBusinessDays(
                deliveredDate, 7, BusinessDayType.PRECISE, GeneralBusinessDayConfig.DEFAULT);
    }

    /** Earliest a loan may consummate after the consumer receives the CD — 3 precise business days. */
    public LocalDate earliestConsummationForCd(LocalDate computedReceivedDate) {
        return calculator.addBusinessDays(
                computedReceivedDate, 3, BusinessDayType.PRECISE, GeneralBusinessDayConfig.DEFAULT);
    }

    /**
     * Deadline to deliver a revised Loan Estimate after a valid changed circumstance — no later than
     * 3 <em>general</em> business days after the creditor receives information sufficient to establish
     * the changed circumstance (12 CFR 1026.19(e)(3)(iv)/(e)(4)). The clock runs from the date the
     * changed circumstance is recognized (here, the accepted-CoC decision date).
     */
    public LocalDate revisedLeDeadline(LocalDate decisionDate) {
        return calculator.addBusinessDays(
                decisionDate, 3, BusinessDayType.GENERAL, GeneralBusinessDayConfig.DEFAULT);
    }
}

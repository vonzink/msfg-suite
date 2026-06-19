package com.msfg.los.loan.domain;

/**
 * Role a real-estate agent plays on a loan transaction.
 * Persisted as its string name to {@code loan_agent.agent_role varchar(40)}.
 * The DB column default is {@code 'BUYERS_AGENT'} — this enum mirrors that.
 */
public enum AgentRole {
    BUYERS_AGENT,
    LISTING_AGENT,
    DUAL_AGENT
}

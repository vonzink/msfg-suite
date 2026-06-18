package com.msfg.los.conditions.domain;

/**
 * Optional underwriting-condition category (when in the loan lifecycle the condition must clear).
 * Persisted as the enum NAME (varchar), nullable — values match the mortgage-app source.
 */
public enum ConditionType {
    PriorToDocs,
    PriorToFunding,
    AtClosing,
    PostClose,
    Other
}

package dev.osc.automation.validation;

public record ValidationViolation(String ruleApiName, String errorMessage) {}

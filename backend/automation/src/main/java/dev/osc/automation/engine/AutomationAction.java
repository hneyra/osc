package dev.osc.automation.engine;

import java.util.Map;

public record AutomationAction(ActionType type, Map<String, Object> params) {}

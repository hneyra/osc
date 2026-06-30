package dev.osc.ai.script;

import reactor.core.publisher.Mono;

/**
 * Port for contacting the AI provider to propose a Kotlin script based on a natural language description.
 */
public interface ScriptAiPort {
    Mono<String> propose(String description, String objectApiName, String kind, String triggerEvent);
}

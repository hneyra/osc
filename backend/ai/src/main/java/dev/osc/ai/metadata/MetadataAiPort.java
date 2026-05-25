package dev.osc.ai.metadata;

import reactor.core.publisher.Mono;

/** Port: AI call that converts a natural-language description to metadata JSON. */
public interface MetadataAiPort {
    Mono<String> suggest(String description);
}

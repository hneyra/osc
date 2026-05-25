package dev.osc.query;

import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

/**
 * Secondary port — translates a parsed SelectQuery AST into a parameterized SQL query.
 * Validates object/field names against metadata. Injects tenant filter. Applies FLS.
 * Tenant ID comes from the caller (usually from Reactor Context).
 */
public interface QueryTranslator {

    /**
     * @param query         parsed AST
     * @param tenantId      resolved tenant for this request
     * @param allowedFields field API names the caller is permitted to see (FLS);
     *                      empty set means no restriction (Phase 1 default)
     */
    Mono<TranslatedQuery> translate(SelectQuery query, UUID tenantId, Set<String> allowedFields);
}

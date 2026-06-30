package dev.osc.scripting

import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.script.experimental.api.CompiledScript

/**
 * Tenant-scoped Caffeine compiled script cache (ADR-005).
 * Keyed by (tenant_id, script_id, contentHash).
 */
@Component
class CompiledScriptCache {

    private val cache = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build<ScriptCacheKey, CompiledScript>()

    fun get(tenantId: UUID, scriptId: UUID, contentHash: String): CompiledScript? {
        val key = ScriptCacheKey(tenantId, scriptId, contentHash)
        return cache.getIfPresent(key)
    }

    fun put(tenantId: UUID, scriptId: UUID, contentHash: String, compiledScript: CompiledScript) {
        val key = ScriptCacheKey(tenantId, scriptId, contentHash)
        cache.put(key, compiledScript)
    }

    fun invalidate(tenantId: UUID, scriptId: UUID) {
        val keysToInvalidate = cache.asMap().keys.filter { it.tenantId == tenantId && it.scriptId == scriptId }
        cache.invalidateAll(keysToInvalidate)
    }
}

data class ScriptCacheKey(
    val tenantId: UUID,
    val scriptId: UUID,
    val contentHash: String
)

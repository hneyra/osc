package dev.osc.scripting

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.util.UUID

class KotlinScriptCompilerServiceTest {

    private lateinit var cache: CompiledScriptCache
    private lateinit var compilerService: KotlinScriptCompilerService
    private lateinit var repository: ScriptRepository
    private lateinit var service: ScriptService
    private val objectMapper = ObjectMapper()

    private val tenantId = UUID.randomUUID()
    private val objectId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        cache = CompiledScriptCache()
        compilerService = KotlinScriptCompilerService(cache)
        repository = mock(ScriptRepository::class.java)
        service = ScriptService(repository, compilerService, objectMapper)
    }

    @Test
    fun `test valid script compilation compiles and caches successfully`() {
        val validSource = """
            import java.time.Instant
            import java.util.UUID
            
            val now = Instant.now()
            val id = UUID.randomUUID()
        """.trimIndent()

        val result = compilerService.compile(tenantId, UUID.randomUUID(), validSource)
        assertTrue(result.errors.isEmpty(), "Expected no compilation errors, got: ${result.errors}")
        assertNotNull(result.compiledScript, "Compiled script should not be null")

        // Compile again to verify cache hit
        val result2 = compilerService.compile(tenantId, UUID.randomUUID(), validSource)
        assertTrue(result2.errors.isEmpty())
        assertNotNull(result2.compiledScript)
    }

    @Test
    fun `test disallowed import is rejected by static analysis`() {
        val forbiddenSource = """
            import java.io.File
            
            val file = File("secrets.txt")
        """.trimIndent()

        val result = compilerService.compile(tenantId, UUID.randomUUID(), forbiddenSource)
        assertFalse(result.errors.isEmpty(), "Expected import compilation to fail")
        assertTrue(result.errors.any { it.contains("Disallowed import: 'java.io.File'") })
        assertNull(result.compiledScript)
    }

    @Test
    fun `test fully qualified forbidden class is rejected by security analysis`() {
        val maliciousSource = """
            val file = java.io.File("secrets.txt")
        """.trimIndent()

        val result = compilerService.compile(tenantId, UUID.randomUUID(), maliciousSource)
        assertFalse(result.errors.isEmpty())
        assertTrue(result.errors.any { it.contains("Security Violation: I/O operations (java.io) are prohibited.") })
        assertNull(result.compiledScript)
    }

    @Test
    fun `test compilation errors are populated for invalid syntax`() {
        val invalidSource = """
            val x = 10
            unresolvedNameHere
        """.trimIndent()

        val result = compilerService.compile(tenantId, UUID.randomUUID(), invalidSource)
        assertFalse(result.errors.isEmpty(), "Expected compiler errors for syntax/semantic error")
        assertTrue(result.errors.any { it.contains("unresolved reference") || it.contains("Unresolved reference") })
        assertNull(result.compiledScript)
    }

    @Test
    fun `test cache hit and miss works as expected`() {
        val source = "val a = 42"
        val scriptId = UUID.randomUUID()
        
        // 1. Initial compile is a Cache Miss
        val result1 = compilerService.compile(tenantId, scriptId, source)
        assertNotNull(result1.compiledScript)
        
        // Verify put into cache
        val hash = calculateHash(source)
        val cached = cache.get(tenantId, scriptId, hash)
        assertNotNull(cached)
        assertSame(result1.compiledScript, cached)

        // 2. Second compile of same source is a Cache Hit
        val result2 = compilerService.compile(tenantId, scriptId, source)
        assertSame(result1.compiledScript, result2.compiledScript)
    }

    @Test
    fun `test cache invalidation works`() {
        val source = "val a = 100"
        val scriptId = UUID.randomUUID()
        
        compilerService.compile(tenantId, scriptId, source)
        assertNotNull(cache.get(tenantId, scriptId, calculateHash(source)))

        cache.invalidate(tenantId, scriptId)
        assertNull(cache.get(tenantId, scriptId, calculateHash(source)))
    }

    @Test
    fun `test save valid inactive script compiles and persists compile errors empty`() {
        val source = "val x = 1"
        val script = Script(
            tenantId = tenantId,
            objectId = objectId,
            kind = "TRIGGER",
            triggerEvent = "BEFORE_INSERT",
            source = source,
            isActive = false
        )

        `when`(repository.save(anyKotlin())).thenAnswer { Mono.just(it.getArgument(0) as Script) }

        StepVerifier.create(service.save(script))
            .assertNext { saved ->
                assertEquals("[]", saved.compileErrorsJson)
                assertNotNull(saved.compiledAt)
                assertFalse(saved.isActive)
            }
            .verifyComplete()
    }

    @Test
    fun `test saving active script with compile errors throws exception NNG-023`() {
        val invalidSource = "invalid code structure"
        val script = Script(
            tenantId = tenantId,
            objectId = objectId,
            kind = "TRIGGER",
            triggerEvent = "BEFORE_INSERT",
            source = invalidSource,
            isActive = true
        )

        StepVerifier.create(service.save(script))
            .expectError(IllegalStateException::class.java)
            .verify()
    }

    @Test
    fun `test saving inactive script with compile errors succeeds but persists compile errors`() {
        val invalidSource = "invalid code structure"
        val script = Script(
            tenantId = tenantId,
            objectId = objectId,
            kind = "TRIGGER",
            triggerEvent = "BEFORE_INSERT",
            source = invalidSource,
            isActive = false
        )

        `when`(repository.save(anyKotlin())).thenAnswer { Mono.just(it.getArgument(0) as Script) }

        StepVerifier.create(service.save(script))
            .assertNext { saved ->
                assertNotEquals("[]", saved.compileErrorsJson)
                assertNotNull(saved.compiledAt)
                assertFalse(saved.isActive, "Script with compile errors should be forced inactive")
            }
            .verifyComplete()
    }

    private fun calculateHash(source: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(source.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyKotlin(): T {
        any<T>()
        return null as T
    }
}

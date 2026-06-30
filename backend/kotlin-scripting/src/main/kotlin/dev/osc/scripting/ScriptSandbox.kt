package dev.osc.scripting

import dev.osc.scripting.api.ExecutionContext
import java.lang.management.ManagementFactory
import java.net.URLClassLoader
import java.util.UUID
import java.util.concurrent.*
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

class RestrictedParentClassLoader : ClassLoader(getSystemClassLoader()) {
    private val allowedPrefixes = listOf(
        "kotlin.",
        "org.jetbrains.kotlin.",
        "java.lang.",
        "java.util.",
        "java.time.",
        "java.math.",
        "dev.osc.scripting.api.",
        "dev.osc.security.",
        "java.security."
    )

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        val isAllowed = allowedPrefixes.any { name.startsWith(it) }
        if (!isAllowed) {
            throw ClassNotFoundException("Class loading blocked by sandbox: $name")
        }
        return super.loadClass(name, resolve)
    }
}

class ScriptSandbox {

    private val tenantClassLoaders = ConcurrentHashMap<UUID, URLClassLoader>()
    private val scriptingHost = BasicJvmScriptingHost()

    fun getTenantClassLoader(tenantId: UUID): URLClassLoader {
        return tenantClassLoaders.computeIfAbsent(tenantId) {
            val urls = emptyArray<java.net.URL>()
            val parent = RestrictedParentClassLoader()
            URLClassLoader(urls, parent)
        }
    }

    fun execute(compiledScript: CompiledScript, context: ExecutionContext, timeoutSeconds: Int): ResultWithDiagnostics<EvaluationResult> {
        val tenantId = context.tenantId
        val safeTimeout = timeoutSeconds.coerceIn(1, 30)
        val tenantClassLoader = getTenantClassLoader(tenantId)

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable).apply {
                name = "script-sandbox-${tenantId}-${UUID.randomUUID().toString().take(8)}"
                contextClassLoader = tenantClassLoader
            }
        }

        val future = executor.submit(Callable {
            val evaluationConfiguration = ScriptEvaluationConfiguration {
                constructorArgs(context)
            }

            val guardianExecutor = Executors.newSingleThreadScheduledExecutor()
            val currentThreadId = Thread.currentThread().id
            val threadMXBean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
            val cpuLimitNano = safeTimeout * 1_000_000_000L
            val initialAllocatedBytes = threadMXBean?.getThreadAllocatedBytes(currentThreadId) ?: 0L
            val maxMemoryAllocation = 50 * 1024 * 1024L // 50MB allocation limit

            val guardianTask = guardianExecutor.scheduleAtFixedRate({
                try {
                    if (threadMXBean != null && threadMXBean.isThreadCpuTimeSupported) {
                        val cpuTime = threadMXBean.getThreadCpuTime(currentThreadId)
                        if (cpuTime > cpuLimitNano) {
                            executor.shutdownNow()
                            throw SecurityException("CPU limit exceeded ($cpuTime ns)")
                        }
                    }

                    if (threadMXBean != null && threadMXBean.isThreadAllocatedMemorySupported) {
                        val currentAllocated = threadMXBean.getThreadAllocatedBytes(currentThreadId)
                        val allocatedSinceStart = currentAllocated - initialAllocatedBytes
                        if (allocatedSinceStart > maxMemoryAllocation) {
                            executor.shutdownNow()
                            throw SecurityException("Heap allocation limit exceeded ($allocatedSinceStart bytes)")
                        }
                    }
                } catch (e: Exception) {
                    // Guardian failsafe
                }
            }, 10, 10, TimeUnit.MILLISECONDS)

            try {
                kotlinx.coroutines.runBlocking {
                    scriptingHost.evaluator(compiledScript, evaluationConfiguration)
                }
            } finally {
                guardianTask.cancel(true)
                guardianExecutor.shutdownNow()
            }
        })

        try {
            return future.get(safeTimeout.toLong(), TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw TimeoutException("Script execution timed out after ${safeTimeout}s")
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is SecurityException) throw cause
            throw e
        } finally {
            executor.shutdownNow()
        }
    }
}

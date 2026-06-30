package dev.osc.scripting

import dev.osc.metadata.MetadataEngine
import dev.osc.metadata.TenantContext
import dev.osc.persistence.DynamicPersistenceService
import dev.osc.persistence.RecordEntity
import dev.osc.query.QueryExecutor
import dev.osc.query.QueryParser
import dev.osc.query.QueryTranslator
import dev.osc.scripting.api.DynamicRecord
import dev.osc.scripting.api.ExecutionContext
import dev.osc.scripting.api.RecordOperations
import dev.osc.security.FlsFilter
import dev.osc.security.PermissionChecker
import dev.osc.security.SecurityContext
import reactor.core.publisher.Mono
import java.util.UUID

class RecordOperationsImpl<T>(
    private val objectApiName: String,
    private val ctx: ExecutionContext,
    private val persistenceService: DynamicPersistenceService,
    private val queryParser: QueryParser,
    private val queryTranslator: QueryTranslator,
    private val queryExecutor: QueryExecutor,
    private val permissionChecker: PermissionChecker,
    private val flsFilter: FlsFilter,
    private val metadataEngine: MetadataEngine
) : RecordOperations<T> {

    override fun findById(id: UUID): DynamicRecord? {
        ctx.checkGuards()
        val tenantId = ctx.tenantId
        val user = ctx.currentUser

        val mono = permissionChecker.canRead(tenantId, user.userId(), objectApiName)
            .flatMap { allowed ->
                if (!allowed) {
                    Mono.error<DynamicRecord>(SecurityException("User lacks READ permission on $objectApiName"))
                } else {
                    persistenceService.getRecord(id)
                        .flatMap { record ->
                            val recordMap = recordToMap(record)
                            flsFilter.apply(recordMap as Map<String, Any>, tenantId, user.userId(), objectApiName)
                                .map { filteredMap ->
                                    DynamicRecord(objectApiName, filteredMap.toMutableMap())
                                }
                        }
                }
            }

        return mono
            .contextWrite { context ->
                context
                    .put(TenantContext.TENANT_ID_KEY, tenantId.toString())
                    .put(SecurityContext.USER_CONTEXT_KEY, user)
            }
            .block()
    }

    override fun query(soql: String): List<DynamicRecord> {
        ctx.checkGuards()
        val tenantId = ctx.tenantId
        val user = ctx.currentUser

        val mono = Mono.fromCallable { queryParser.parse(soql) }
            .flatMap { ast ->
                val targetObjectName = ast.objectName
                permissionChecker.canRead(tenantId, user.userId(), targetObjectName)
                    .flatMap { allowed ->
                        if (!allowed) {
                            Mono.error<List<DynamicRecord>>(SecurityException("User lacks READ permission on $targetObjectName"))
                        } else {
                            permissionChecker.allowedReadFields(tenantId, user.userId(), targetObjectName)
                                .flatMap { allowedFields ->
                                    queryTranslator.translate(ast, tenantId, allowedFields)
                                        .flatMap { translated ->
                                            queryExecutor.execute(translated)
                                                .map { rowMap ->
                                                    val filteredRow = rowMap.filterKeys { key ->
                                                        key == "id" || key == "objectId" || key == "ownerId" || key == "createdAt" || key == "updatedAt" ||
                                                                allowedFields.isEmpty() || allowedFields.contains(key)
                                                    }
                                                    DynamicRecord(targetObjectName, filteredRow.toMutableMap())
                                                }
                                                .collectList()
                                        }
                                }
                        }
                    }
            }

        return mono
            .contextWrite { context ->
                context
                    .put(TenantContext.TENANT_ID_KEY, tenantId.toString())
                    .put(SecurityContext.USER_CONTEXT_KEY, user)
            }
            .block() ?: emptyList()
    }

    override fun insert(record: DynamicRecord): DynamicRecord {
        ctx.checkGuards()
        val tenantId = ctx.tenantId
        val user = ctx.currentUser

        val mono = permissionChecker.canCreate(tenantId, user.userId(), record.objectApiName)
            .flatMap { allowed ->
                if (!allowed) {
                    Mono.error<DynamicRecord>(SecurityException("User lacks CREATE permission on ${record.objectApiName}"))
                } else {
                    val inputData = record.fields.filterKeys {
                        it != "id" && it != "objectId" && it != "tenantId" && it != "createdAt" && it != "updatedAt"
                    }
                    persistenceService.createRecord(record.objectApiName, inputData as Map<String, Any>)
                        .flatMap { entity ->
                            val recordMap = recordToMap(entity)
                            permissionChecker.allowedReadFields(tenantId, user.userId(), record.objectApiName)
                                .map { allowedFields ->
                                    val filteredMap = recordMap.filterKeys { key ->
                                        key == "id" || key == "objectId" || key == "ownerId" || key == "createdAt" || key == "updatedAt" ||
                                                allowedFields.isEmpty() || allowedFields.contains(key)
                                    }
                                    DynamicRecord(record.objectApiName, filteredMap.toMutableMap())
                                }
                        }
                }
            }

        return mono
            .contextWrite { context ->
                context
                    .put(TenantContext.TENANT_ID_KEY, tenantId.toString())
                    .put(SecurityContext.USER_CONTEXT_KEY, user)
            }
            .block() ?: throw IllegalStateException("Failed to insert record")
    }

    override fun update(record: DynamicRecord): DynamicRecord {
        ctx.checkGuards()
        val tenantId = ctx.tenantId
        val user = ctx.currentUser
        val recordId = record.id ?: throw IllegalArgumentException("Cannot update record without id")

        val mono = permissionChecker.canEdit(tenantId, user.userId(), record.objectApiName)
            .flatMap { allowed ->
                if (!allowed) {
                    Mono.error<DynamicRecord>(SecurityException("User lacks EDIT permission on ${record.objectApiName}"))
                } else {
                    val inputData = record.fields.filterKeys {
                        it != "id" && it != "objectId" && it != "tenantId" && it != "createdAt" && it != "updatedAt"
                    }
                    persistenceService.updateRecord(recordId, inputData as Map<String, Any>)
                        .flatMap { entity ->
                            val recordMap = recordToMap(entity)
                            permissionChecker.allowedReadFields(tenantId, user.userId(), record.objectApiName)
                                .map { allowedFields ->
                                    val filteredMap = recordMap.filterKeys { key ->
                                        key == "id" || key == "objectId" || key == "ownerId" || key == "createdAt" || key == "updatedAt" ||
                                                allowedFields.isEmpty() || allowedFields.contains(key)
                                    }
                                    DynamicRecord(record.objectApiName, filteredMap.toMutableMap())
                                }
                        }
                }
            }

        return mono
            .contextWrite { context ->
                context
                    .put(TenantContext.TENANT_ID_KEY, tenantId.toString())
                    .put(SecurityContext.USER_CONTEXT_KEY, user)
            }
            .block() ?: throw IllegalStateException("Failed to update record")
    }

    override fun delete(id: UUID) {
        ctx.checkGuards()
        val tenantId = ctx.tenantId
        val user = ctx.currentUser

        val mono = permissionChecker.canDelete(tenantId, user.userId(), objectApiName)
            .flatMap { allowed ->
                if (!allowed) {
                    Mono.error<Void>(SecurityException("User lacks DELETE permission on $objectApiName"))
                } else {
                    persistenceService.deleteRecord(id)
                }
            }

        mono
            .contextWrite { context ->
                context
                    .put(TenantContext.TENANT_ID_KEY, tenantId.toString())
                    .put(SecurityContext.USER_CONTEXT_KEY, user)
            }
            .block()
    }

    private fun recordToMap(r: RecordEntity): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["id"] = r.id()
        map["objectId"] = r.objectId()
        if (r.name() != null) map["name"] = r.name()
        if (r.ownerId() != null) map["ownerId"] = r.ownerId()
        if (r.data() != null) map.putAll(r.data())
        if (r.createdAt() != null) map["createdAt"] = r.createdAt()
        if (r.updatedAt() != null) map["updatedAt"] = r.updatedAt()
        return map
    }
}

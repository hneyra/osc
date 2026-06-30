package dev.osc.scripting.api

import java.util.UUID

interface RecordOperations<T> {
    fun findById(id: UUID): DynamicRecord?
    fun query(soql: String): List<DynamicRecord>
    fun insert(record: DynamicRecord): DynamicRecord
    fun update(record: DynamicRecord): DynamicRecord
    fun delete(id: UUID)
}

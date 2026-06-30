package dev.osc.scripting.api

interface TriggerContext {
    val event: TriggerEvent
    val newRecords: List<DynamicRecord>
    val oldRecords: List<DynamicRecord>
    val errors: MutableList<String>
    fun addError(message: String)
}

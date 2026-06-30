package dev.osc.scripting

import dev.osc.scripting.api.DynamicRecord
import dev.osc.scripting.api.TriggerContext
import dev.osc.scripting.api.TriggerEvent

class TriggerContextImpl(
    override val event: TriggerEvent,
    override val newRecords: List<DynamicRecord> = emptyList(),
    override val oldRecords: List<DynamicRecord> = emptyList()
) : TriggerContext {
    override val errors = mutableListOf<String>()

    override fun addError(message: String) {
        errors.add(message)
    }
}

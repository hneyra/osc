package dev.osc.scripting.api

import java.util.UUID

class DynamicRecord(
    val objectApiName: String,
    val fields: MutableMap<String, Any?> = mutableMapOf()
) {
    var id: UUID?
        get() = when (val value = fields["id"]) {
            is UUID -> value
            is String -> UUID.fromString(value)
            else -> null
        }
        set(value) {
            fields["id"] = value
        }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(name: String): T? = fields[name] as? T

    fun set(name: String, value: Any?) {
        fields[name] = value
    }

    override fun toString(): String {
        return "DynamicRecord(objectApiName='$objectApiName', fields=$fields)"
    }
}

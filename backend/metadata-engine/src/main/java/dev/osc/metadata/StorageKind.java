package dev.osc.metadata;

public enum StorageKind {
    /** Field is stored in a dedicated column (promoted from JSONB for performance). */
    COLUMN,
    /** Field is stored inside the record.data JSONB column. */
    JSONB
}

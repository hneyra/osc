package dev.osc.ai.metadata;

import java.util.List;

public record MetadataSuggestion(
        String objectApiName,
        String label,
        String labelPlural,
        List<FieldSuggestion> fields
) {}

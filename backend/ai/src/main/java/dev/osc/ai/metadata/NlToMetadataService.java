package dev.osc.ai.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

public class NlToMetadataService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MetadataAiPort aiPort;

    public NlToMetadataService(MetadataAiPort aiPort) {
        this.aiPort = aiPort;
    }

    public Mono<MetadataSuggestion> suggest(String description) {
        if (description == null || description.isBlank()) {
            return Mono.error(new IllegalArgumentException("description must not be blank"));
        }
        return aiPort.suggest(description)
                .map(this::parse);
    }

    private MetadataSuggestion parse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            String objectApiName = root.path("objectApiName").asText();
            String label         = root.path("label").asText();
            String labelPlural   = root.path("labelPlural").asText();

            List<FieldSuggestion> fields = new ArrayList<>();
            for (JsonNode f : root.path("fields")) {
                fields.add(new FieldSuggestion(
                        f.path("apiName").asText(),
                        f.path("label").asText(),
                        f.path("fieldType").asText()
                ));
            }
            return new MetadataSuggestion(objectApiName, label, labelPlural, fields);
        } catch (Exception e) {
            throw new MetadataSuggestionParseException("Cannot parse AI metadata response: " + json, e);
        }
    }
}

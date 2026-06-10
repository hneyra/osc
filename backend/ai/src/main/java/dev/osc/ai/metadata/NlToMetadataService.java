package dev.osc.ai.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class NlToMetadataService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MetadataAiPort aiPort;
    private final MetadataContractValidator contractValidator;

    public NlToMetadataService(MetadataAiPort aiPort) {
        this(aiPort, new MetadataContractValidator());
    }

    public NlToMetadataService(MetadataAiPort aiPort, MetadataContractValidator contractValidator) {
        this.aiPort = aiPort;
        this.contractValidator = contractValidator;
    }

    public Mono<MetadataSuggestion> suggest(String description) {
        if (description == null || description.isBlank()) {
            return Mono.error(new IllegalArgumentException("description must not be blank"));
        }
        return aiPort.suggest(description)
                .map(this::parse);
    }

    private MetadataSuggestion parse(String json) {
        MetadataSuggestion suggestion;
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
            suggestion = new MetadataSuggestion(objectApiName, label, labelPlural, fields);
        } catch (Exception e) {
            throw new MetadataSuggestionParseException("Cannot parse AI metadata response: " + json, e);
        }
        // The AI proposes, the engine disposes: reject anything that does not satisfy the
        // published ObjectDefinition contract (docs/contracts/metadata-object-schema.json).
        validateAgainstContract(suggestion);
        return suggestion;
    }

    private void validateAgainstContract(MetadataSuggestion suggestion) {
        ObjectNode object = MAPPER.createObjectNode();
        object.put("apiName", suggestion.objectApiName());
        object.put("label", suggestion.label());
        object.put("labelPlural", suggestion.labelPlural());
        ArrayNode fields = object.putArray("fields");
        for (FieldSuggestion field : suggestion.fields()) {
            ObjectNode fieldNode = fields.addObject();
            fieldNode.put("apiName", field.apiName());
            fieldNode.put("label", field.label());
            fieldNode.put("fieldType", field.fieldType());
        }

        Set<ValidationMessage> violations = contractValidator.validateObject(object);
        if (!violations.isEmpty()) {
            throw new MetadataSuggestionParseException(
                    "AI metadata violates the ObjectDefinition contract: " + violations);
        }
    }
}

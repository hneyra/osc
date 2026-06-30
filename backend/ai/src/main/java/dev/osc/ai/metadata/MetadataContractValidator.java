package dev.osc.ai.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;

/**
 * Validates metadata against the published JSON Schema contracts in {@code docs/contracts/}.
 *
 * The schemas are the single source of truth for the shape of objects, fields, validation
 * rules and layouts. They are copied onto the classpath under {@code contracts/} by the
 * module build, so this validator enforces the same contract that the documentation publishes.
 *
 * This is the "the AI proposes, the engine disposes" guardrail: no AI-generated metadata is
 * accepted unless it validates against these schemas.
 */
public class MetadataContractValidator {

    private final JsonSchema objectSchema;
    private final JsonSchema fieldSchema;
    private final JsonSchema validationRuleSchema;
    private final JsonSchema layoutSchema;

    public MetadataContractValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        this.objectSchema = load(factory, "contracts/metadata-object-schema.json");
        this.fieldSchema = load(factory, "contracts/metadata-field-schema.json");
        this.validationRuleSchema = load(factory, "contracts/metadata-validation-rule-schema.json");
        this.layoutSchema = load(factory, "contracts/metadata-layout-schema.json");
    }

    /** Returns the set of violations of the ObjectDefinition contract; empty means valid. */
    public Set<ValidationMessage> validateObject(JsonNode node) {
        Set<ValidationMessage> violations = objectSchema.validate(node);
        if (!violations.isEmpty()) {
            return violations;
        }
        if (node.has("fields") && node.get("fields").isArray()) {
            for (JsonNode field : node.get("fields")) {
                validateFormulaField(field);
            }
        }
        return violations;
    }

    /** Returns the set of violations of the FieldDefinition contract; empty means valid. */
    public Set<ValidationMessage> validateField(JsonNode node) {
        Set<ValidationMessage> violations = fieldSchema.validate(node);
        if (!violations.isEmpty()) {
            return violations;
        }
        validateFormulaField(node);
        return violations;
    }

    private void validateFormulaField(JsonNode node) {
        if (node.has("field_type") && "FORMULA".equals(node.get("field_type").asText())) {
            JsonNode config = node.path("config");
            if (config.has("formula")) {
                String formula = config.get("formula").asText();
                if (java.util.regex.Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\.[a-zA-Z_]").matcher(formula).find()) {
                    throw new IllegalArgumentException("Cross-object references in formulas are not allowed: " + formula);
                }
            }
        }
    }

    /** Returns the set of violations of the ValidationRule contract; empty means valid. */
    public Set<ValidationMessage> validateValidationRule(JsonNode node) {
        return validationRuleSchema.validate(node);
    }

    /** Returns the set of violations of the Layout contract; empty means valid. */
    public Set<ValidationMessage> validateLayout(JsonNode node) {
        return layoutSchema.validate(node);
    }

    private static JsonSchema load(JsonSchemaFactory factory, String resource) {
        try (InputStream in = MetadataContractValidator.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Contract schema not found on classpath: " + resource);
            }
            return factory.getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load contract schema: " + resource, e);
        }
    }
}

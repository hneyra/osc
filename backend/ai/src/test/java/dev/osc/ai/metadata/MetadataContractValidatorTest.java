package dev.osc.ai.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("MetadataContractValidator")
class MetadataContractValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Construction loads and compiles all four Draft-07 schemas from the classpath.
    private final MetadataContractValidator validator = new MetadataContractValidator();

    @Test
    @DisplayName("the four contract schemas load and compile from the classpath")
    void schemasLoad() {
        assertThat(validator).isNotNull();
    }

    @Test
    @DisplayName("the Account example validates against the ObjectDefinition schema")
    void accountExampleValidates() throws Exception {
        assertThat(validator.validateObject(example("examples/account.json"))).isEmpty();
    }

    @Test
    @DisplayName("the Contact example validates against the ObjectDefinition schema")
    void contactExampleValidates() throws Exception {
        assertThat(validator.validateObject(example("examples/contact.json"))).isEmpty();
    }

    @Test
    @DisplayName("an invalid api_name is rejected")
    void invalidApiNameRejected() throws Exception {
        JsonNode bad = MAPPER.readTree("{\"apiName\":\"1bad\",\"label\":\"Bad\",\"labelPlural\":\"Bads\"}");
        assertThat(validator.validateObject(bad)).isNotEmpty();
    }

    @Test
    @DisplayName("an unknown field type is rejected")
    void unknownFieldTypeRejected() throws Exception {
        JsonNode bad = MAPPER.readTree("""
                {"apiName":"Acc","label":"Acc","labelPlural":"Accs",
                 "fields":[{"apiName":"x","label":"X","fieldType":"NOPE"}]}""");
        assertThat(validator.validateObject(bad)).isNotEmpty();
    }

    @Test
    @DisplayName("a PICKLIST field without picklistValues fails the FieldDefinition schema")
    void picklistRequiresValues() throws Exception {
        JsonNode bad = MAPPER.readTree("""
                {"api_name":"industry__c","label":"Industry","field_type":"PICKLIST","storage_kind":"JSONB"}""");
        assertThat(validator.validateField(bad)).isNotEmpty();
    }

    @Test
    @DisplayName("a valid FORMULA field is accepted")
    void validFormulaAccepted() throws Exception {
        JsonNode good = MAPPER.readTree("""
                {"api_name":"discount__c","label":"Discount","field_type":"FORMULA","storage_kind":"JSONB",
                 "config":{"formula":"amount * 0.1"}}""");
        assertThat(validator.validateField(good)).isEmpty();
    }

    @Test
    @DisplayName("a FORMULA field with cross-object references is rejected")
    void crossObjectFormulaRejected() throws Exception {
        JsonNode bad = MAPPER.readTree("""
                {"api_name":"parent_name__c","label":"Parent Name","field_type":"FORMULA","storage_kind":"JSONB",
                 "config":{"formula":"Account.Name"}}""");
        assertThrows(IllegalArgumentException.class, () -> validator.validateField(bad));
    }

    private static JsonNode example(String resource) throws Exception {
        try (InputStream in = MetadataContractValidatorTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("test resource %s", resource).isNotNull();
            return MAPPER.readTree(in);
        }
    }
}

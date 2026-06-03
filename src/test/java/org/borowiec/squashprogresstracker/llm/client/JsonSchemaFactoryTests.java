package org.borowiec.squashprogresstracker.llm.client;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonSchemaFactoryTests {

    private final JsonSchemaFactory factory = new JsonSchemaFactory(new ObjectMapper());

    record FlatRecord(String name, int score, boolean active) {}
    record NestedRecord(String label, FlatRecord details) {}
    record ListRecord(String title, List<String> tags) {}

    @Test
    void schemaFor_flatRecord_producesObjectWithRequiredFields() {
        var schema = (ObjectNode) factory.schemaFor(FlatRecord.class);

        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("properties").has("name")).isTrue();
        assertThat(schema.path("properties").path("name").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").has("score")).isTrue();
        assertThat(schema.path("properties").path("score").path("type").asText()).isEqualTo("integer");
        assertThat(schema.path("properties").has("active")).isTrue();
        assertThat(schema.path("properties").path("active").path("type").asText()).isEqualTo("boolean");
        assertThat(schema.path("required").isArray()).isTrue();
        assertThat(schema.path("required")).hasSize(3);
    }

    @Test
    void schemaFor_nestedRecord_inlinesNestedSchema() {
        var schema = (ObjectNode) factory.schemaFor(NestedRecord.class);

        var detailsSchema = schema.path("properties").path("details");
        assertThat(detailsSchema.path("type").asText()).isEqualTo("object");
        assertThat(detailsSchema.path("properties").has("name")).isTrue();
    }

    @Test
    void schemaFor_listField_producesArrayType() {
        var schema = (ObjectNode) factory.schemaFor(ListRecord.class);

        var tagsSchema = schema.path("properties").path("tags");
        assertThat(tagsSchema.path("type").asText()).isEqualTo("array");
        assertThat(tagsSchema.path("items").path("type").asText()).isEqualTo("string");
    }

    @Test
    void schemaFor_nonRecord_throwsIllegalArgument() {
        assertThatThrownBy(() -> factory.schemaFor(String.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only record types");
    }
}

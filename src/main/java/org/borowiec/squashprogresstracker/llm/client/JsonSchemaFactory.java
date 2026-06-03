package org.borowiec.squashprogresstracker.llm.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

/**
 * Derives a JSON Schema ObjectNode from a Java record type.
 * Supported field types: String, int/Integer, long/Long, double/Double,
 * float/Float, boolean/Boolean, nested records, and Collection<T>.
 * Unsupported types throw {@link IllegalArgumentException}.
 */
class JsonSchemaFactory {

    private final ObjectMapper objectMapper;

    JsonSchemaFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode schemaFor(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Only record types are supported for schema derivation: " + type.getName());
        }
        var schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        var properties = schema.putObject("properties");
        var required = schema.putArray("required");
        for (var component : type.getRecordComponents()) {
            properties.set(component.getName(), fieldSchema(component.getType(), component.getGenericType()));
            required.add(component.getName());
        }
        return schema;
    }

    private ObjectNode fieldSchema(Class<?> type, Type genericType) {
        var node = objectMapper.createObjectNode();
        if (type == String.class) {
            node.put("type", "string");
        } else if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) {
            node.put("type", "integer");
        } else if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            node.put("type", "number");
        } else if (type == boolean.class || type == Boolean.class) {
            node.put("type", "boolean");
        } else if (Collection.class.isAssignableFrom(type)) {
            node.put("type", "array");
            if (genericType instanceof ParameterizedType pt
                    && pt.getActualTypeArguments().length > 0
                    && pt.getActualTypeArguments()[0] instanceof Class<?> itemClass) {
                node.set("items", fieldSchema(itemClass, itemClass));
            }
        } else if (type.isRecord()) {
            return (ObjectNode) schemaFor(type);
        } else {
            throw new IllegalArgumentException("Unsupported type for JSON schema derivation: " + type.getName());
        }
        return node;
    }
}

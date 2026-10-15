package dev.webhooks.platform.config;

import dev.webhooks.platform.common.ApiNullable;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/**
 * Makes the OpenAPI document exact enough to generate the dashboard's TypeScript types from it
 * (frontend/src/api/schema.ts):
 * <ul>
 *   <li>Every property of a response schema is required, because responses always include every
 *       field. Request schemas keep the requirements from their validation annotations.</li>
 *   <li>Fields marked {@link ApiNullable} are typed as {@code T | null}; all others are non-null.</li>
 *   <li>Jackson {@code JsonNode} payloads are plain JSON objects.</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    static {
        // Event payloads and bodies are arbitrary JSON objects, not Jackson's node API.
        SpringDocUtils.getConfig().replaceWithClass(JsonNode.class, Object.class);
    }

    @Bean
    PropertyCustomizer nullableProperties() {
        return (property, type) -> {
            Annotation[] annotations = type.getCtxAnnotations();
            if (annotations == null || property == null) {
                return property;
            }
            for (Annotation annotation : annotations) {
                if (annotation instanceof ApiNullable) {
                    return nullable(property);
                }
            }
            return property;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Schema nullable(Schema property) {
        if (property.get$ref() != null) {
            Schema wrapper = new Schema();
            wrapper.setAnyOf(new ArrayList<>(List.of(new Schema().$ref(property.get$ref()), nullSchema())));
            return wrapper;
        }
        if (property.getTypes() != null) {
            property.addType("null");
        } else if (property.getType() != null) {
            property.setTypes(new LinkedHashSet<>(List.of(property.getType(), "null")));
        } else {
            property.setNullable(true);
        }
        return property;
    }

    @SuppressWarnings("rawtypes")
    private static Schema nullSchema() {
        Schema schema = new Schema();
        schema.addType("null");
        return schema;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void allowNullInEnum(Schema property) {
        if (property.getEnum() != null && property.getTypes() != null && property.getTypes().contains("null")
                && !property.getEnum().contains(null)) {
            List values = new ArrayList<>(property.getEnum());
            values.add(null);
            property.setEnum(values);
        }
    }

    @Bean
    OpenApiCustomizer responseFieldsAreRequired() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().forEach((name, schema) -> {
                if (schema.getProperties() == null || schema.getProperties().isEmpty()) {
                    return;
                }
                if (!name.endsWith("Request")) {
                    schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
                }
                // A nullable enum must also list null among its values.
                schema.getProperties().values().forEach(property -> allowNullInEnum((Schema) property));
            });
        };
    }
}

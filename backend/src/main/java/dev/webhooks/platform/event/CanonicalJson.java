package dev.webhooks.platform.event;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Produces a key-order-independent form of a JSON tree, used to fingerprint requests for
 * idempotency. Object keys are sorted recursively; array order and number formatting are kept, so
 * {@code 1} and {@code 1.0} are treated as different values.
 */
final class CanonicalJson {

    private CanonicalJson() {}

    static JsonNode canonicalize(JsonNode node) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
            entries.sort(Map.Entry.comparingByKey());
            ObjectNode sorted = factory.objectNode();
            for (Map.Entry<String, JsonNode> entry : entries) {
                sorted.set(entry.getKey(), canonicalize(entry.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = factory.arrayNode();
            for (JsonNode child : node) {
                array.add(canonicalize(child));
            }
            return array;
        }
        return node;
    }
}

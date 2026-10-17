package dev.webhooks.platform.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.webhooks.platform.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;

/**
 * The API contract the dashboard is typed against. {@code frontend/openapi.json} is generated from the
 * running backend; {@code npm run gen:api} turns it into {@code frontend/src/api/schema.ts}. This test
 * fails when the backend's API changes without the committed document being regenerated:
 * <pre>./mvnw -pl backend test -Dtest=OpenApiContractTest -Dopenapi.update=true</pre>
 */
@IntegrationTest
class OpenApiContractTest {

    static final Path CONTRACT = Path.of("..", "frontend", "openapi.json");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void committedContractMatchesTheBackend() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode document = json.readTree(body);
        ((ObjectNode) document).remove("servers"); // differs per environment
        String pretty = json.writer().with(SerializationFeature.INDENT_OUTPUT).writeValueAsString(document) + "\n";

        if (Boolean.getBoolean("openapi.update")) {
            Files.writeString(CONTRACT, pretty);
        }
        assertThat(Files.exists(CONTRACT)).as("run with -Dopenapi.update=true to create " + CONTRACT).isTrue();
        assertThat(Files.readString(CONTRACT))
                .as("API changed: regenerate with -Dopenapi.update=true, then npm run gen:api in frontend/")
                .isEqualTo(pretty);
    }
}

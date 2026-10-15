package dev.webhooks.simulator;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ReceiverControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void respondsWithConfiguredStatusAndLogsDuplicates() throws Exception {
        mvc.perform(put("/receivers/a/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":503,\"delayMs\":0}"))
                .andExpect(status().isOk());

        mvc.perform(post("/receivers/a").header("webhook-id", "evt_1").content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.duplicate").value(false));
        mvc.perform(post("/receivers/a").header("webhook-id", "evt_1").content("{}"))
                .andExpect(jsonPath("$.duplicate").value(true));

        mvc.perform(get("/receivers/a/requests"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].respondedWith").value(503))
                .andExpect(jsonPath("$[1].duplicate").value(true));
    }

    @Test
    void reportsSignatureValidityWhenSecretConfigured() throws Exception {
        mvc.perform(put("/receivers/b/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":200,\"delayMs\":0,\"secret\":\"whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/receivers/b").header("webhook-id", "x").header("webhook-timestamp", "1")
                        .header("webhook-signature", "v1,AAAA").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signature").value("TIMESTAMP_OUT_OF_TOLERANCE"));
    }

    @Test
    void addsConfiguredResponseHeaders() throws Exception {
        mvc.perform(put("/receivers/d/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":429,\"delayMs\":0,\"headers\":{\"Retry-After\":\"7\"}}"))
                .andExpect(status().isOk());
        mvc.perform(post("/receivers/d").content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Retry-After", "7"));
    }

    @Test
    void rejectsInvalidConfiguration() throws Exception {
        mvc.perform(put("/receivers/c/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":42,\"delayMs\":0}"))
                .andExpect(status().isBadRequest());
    }
}

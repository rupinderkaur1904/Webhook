package dev.webhooks.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** A simulator deployed publicly: control token set, a small receiver cap. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"simulator.control-token=test-control-token-0123456789", "simulator.max-receivers=3"})
@AutoConfigureMockMvc
class PublicExposureTest {

    static final String AUTH = "Bearer test-control-token-0123456789";

    @Autowired MockMvc mvc;
    @Autowired ReceiverRegistry registry;
    @LocalServerPort int port;

    @Test
    void deliveriesAndHealthAreOpenButTheControlApiNeedsTheToken() throws Exception {
        mvc.perform(post("/receivers/open").content("{}")).andExpect(status().isOk());
        mvc.perform(get("/healthz")).andExpect(status().isOk());

        mvc.perform(get("/receivers/open/requests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/receivers/open/requests").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
        mvc.perform(put("/receivers/open/config").contentType(MediaType.APPLICATION_JSON).content("{\"status\":500}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/receivers/open")).andExpect(status().isUnauthorized());
        mvc.perform(post("/verify").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());

        mvc.perform(get("/receivers/open/requests").header("Authorization", AUTH)).andExpect(status().isOk());
        mvc.perform(delete("/receivers/open").header("Authorization", AUTH)).andExpect(status().isNoContent());
    }

    @Test
    void receiverCountIsCappedAndNamesAreValidated() throws Exception {
        for (String name : new String[] {"cap-a", "cap-b", "cap-c"}) {
            mvc.perform(delete("/receivers/" + name).header("Authorization", AUTH));
        }
        registry.reset("open");
        mvc.perform(post("/receivers/cap-a").content("{}")).andExpect(status().isOk());
        mvc.perform(post("/receivers/cap-b").content("{}")).andExpect(status().isOk());
        mvc.perform(post("/receivers/cap-c").content("{}")).andExpect(status().isOk());
        mvc.perform(post("/receivers/cap-d").content("{}")).andExpect(status().isTooManyRequests());
        mvc.perform(post("/receivers/cap-a").content("{}")).andExpect(status().isOk());
        mvc.perform(post("/receivers/" + "x".repeat(65)).content("{}")).andExpect(status().isBadRequest());
        for (String name : new String[] {"cap-a", "cap-b", "cap-c"}) {
            mvc.perform(delete("/receivers/" + name).header("Authorization", AUTH)).andExpect(status().isNoContent());
        }
    }

    @Test
    void oversizedBodiesAreRefusedDeclaredOrChunked() throws Exception {
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        byte[] big = new byte[(int) PublicExposureFilter.MAX_BODY_BYTES + 1];
        URI uri = URI.create("http://127.0.0.1:" + port + "/receivers/big");
        int declared = http.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofByteArray(big)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        int chunked = http.send(HttpRequest.newBuilder(uri)
                        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(big))).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
        assertThat(declared).isEqualTo(413);
        assertThat(chunked).isEqualTo(413);
        registry.reset("big");
    }
}

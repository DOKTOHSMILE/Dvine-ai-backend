package ai.dvine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The server's own model: phones use it like an API model; the request reaches llama-server with the server's model. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dvine.bootstrap-enabled=true",
        "dvine.bootstrap-token=test-bootstrap-token",
        "dvine.llm.model=qwen3-14b",
        "dvine.llm.max-tokens=1000",
        "dvine.llm.requests-per-minute=2"
})
class ServerModelTest {
    static final List<String> asked = new ArrayList<>();
    static final HttpServer upstream;

    static {
        try {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // a stand-in for llama-server's /v1/chat/completions
        upstream.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (asked) {
                asked.add(body);
            }
            boolean stream = body.contains("\"stream\":true");
            byte[] out = (stream
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"there.\"}}]}\n\ndata: [DONE]\n\n"
                    : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello there.\"}}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream o = ex.getResponseBody()) {
                o.write(out);
            }
        });
        upstream.start();
    }

    @DynamicPropertySource
    static void llm(DynamicPropertyRegistry r) {
        r.add("dvine.llm.url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        upstream.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void phonesUseTheServersModelLikeAnApiModel() throws Exception {
        // signed-in phones only
        int anonymous = mvc.perform(post("/api/v1/llm/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")).andReturn().getResponse().getStatus();
        assertTrue(anonymous == 401 || anonymous == 403, "status " + anonymous);
        String token = session("phone-llm-a");

        MvcResult list = mvc.perform(get("/api/v1/llm/models").header("Authorization", "Bearer " + token)).andReturn();
        assertEquals(200, list.getResponse().getStatus());
        assertEquals("qwen3-14b", mapper.readTree(list.getResponse().getContentAsString()).at("/data/0/id").asText());

        // a whole reply; the model and the length are the server's to set
        MvcResult whole = mvc.perform(asyncDispatch(mvc.perform(post("/api/v1/llm/chat/completions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"anything\",\"max_completion_tokens\":50000,\"reasoning_effort\":\"low\","
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")).andReturn())).andReturn();
        assertEquals(200, whole.getResponse().getStatus());
        assertEquals("Hello there.", mapper.readTree(whole.getResponse().getContentAsString()).at("/choices/0/message/content").asText());
        JsonNode sent;
        synchronized (asked) {
            sent = mapper.readTree(asked.get(asked.size() - 1));
        }
        assertEquals("qwen3-14b", sent.path("model").asText());
        assertEquals(1000, sent.path("max_tokens").asInt());
        assertFalse(sent.has("max_completion_tokens") || sent.has("reasoning_effort"));

        // streamed, piece by piece
        MvcResult started = mvc.perform(post("/api/v1/llm/chat/completions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")).andReturn();
        MvcResult streamed = mvc.perform(asyncDispatch(started)).andReturn();
        String sse = streamed.getResponse().getContentAsString();
        assertTrue(sse.contains("\"Hello \"") && sse.contains("\"there.\"") && sse.contains("[DONE]"), sse);

        // two a minute here: the third is refused
        assertEquals(429, status(token, "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));
        // no messages: refused before the model is asked
        assertEquals(400, status(session("phone-llm-b"), "{\"messages\":[]}"));
    }

    private int status(String token, String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/llm/chat/completions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        return r.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(r)).andReturn().getResponse().getStatus() : r.getResponse().getStatus();
    }

    private String session(String device) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + device + "\",\"bootstrap\":\"test-bootstrap-token\"}")).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }
}

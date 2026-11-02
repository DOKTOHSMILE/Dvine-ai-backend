package ai.dvine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Without a model server set, the server's model says so (503) instead of failing. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"dvine.bootstrap-enabled=true", "dvine.bootstrap-token=test-bootstrap-token", "dvine.llm.url="})
class ServerModelOffTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void noModelServerMeansAClear503() throws Exception {
        MvcResult s = mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"phone-llm-off\",\"bootstrap\":\"test-bootstrap-token\"}")).andReturn();
        String token = mapper.readTree(s.getResponse().getContentAsString()).get("token").asText();
        MvcResult r = mvc.perform(get("/api/v1/llm/models").header("Authorization", "Bearer " + token)).andReturn();
        assertEquals(503, r.getResponse().getStatus());
        assertEquals("not_configured", mapper.readTree(r.getResponse().getContentAsString()).at("/error/type").asText());
        MvcResult chat = mvc.perform(post("/api/v1/llm/chat/completions").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}")).andReturn();
        if (chat.getRequest().isAsyncStarted()) {
            chat = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(chat)).andReturn();
        }
        assertEquals(503, chat.getResponse().getStatus());
    }
}

package ai.dvine;

import com.fasterxml.jackson.databind.JsonNode;
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

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dvine.bootstrap-enabled=true",
        "dvine.bootstrap-token=test-bootstrap-token"
})
class EpisodesTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    private static final String BODY = "{\"schemaVersion\":1,\"deviceId\":\"phone-1\",\"episodes\":["
            + "{\"id\":\"x1\",\"kind\":\"ACTION\",\"tag\":\"pose:pushups\",\"summary\":\"did push-ups\",\"importance\":0.4,\"occurredAt\":1},"
            + "{\"id\":\"x2\",\"kind\":\"ACTION\",\"tag\":\"pose:pushups\",\"summary\":\"did push-ups\",\"importance\":0.4},"
            + "{\"id\":\"x3\",\"kind\":\"SCENE\",\"tag\":\"scene:morning workout\",\"summary\":\"acted\",\"importance\":0.6},"
            + "{\"id\":\"x4\",\"kind\":\"ACTION\",\"tag\":\"pose:cartwheel\",\"summary\":\"cartwheel\",\"importance\":0.4},"
            + "{\"id\":\"x5\",\"kind\":\"TOUCH\",\"tag\":\"touch:left hand\",\"summary\":\"my pin is 1234\",\"importance\":0.2},"
            + "{\"id\":\"x6\",\"kind\":\"NOPE\",\"tag\":\"pose:x\",\"summary\":\"\",\"importance\":0.1},"
            + "{\"id\":\"x7\",\"kind\":\"ACTION\",\"tag\":\"Bad Tag!\",\"summary\":\"\",\"importance\":0.1}]}";

    @Test
    void episodesAreOwnerScopedIdempotentAndConsolidated() throws Exception {
        assertEquals(401, mvc.perform(post("/api/v1/episodes/sync").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse().getStatus());
        assertEquals(401, mvc.perform(get("/api/v1/episodes/profile")).andReturn().getResponse().getStatus());

        String a = session();
        JsonNode first = sync(a);
        assertEquals(4, first.get("accepted").size());
        assertEquals(3, first.get("rejected").size()); // secret, unknown kind, bad tag
        sync(a); // a replay adds nothing

        JsonNode profile = profile(a);
        assertEquals(4, profile.get("episodes").asInt());
        assertEquals("pushups", profile.get("favourites").get("pose").get(0).asText());
        assertEquals("cartwheel", profile.get("favourites").get("pose").get(1).asText());
        assertEquals("morning workout", profile.get("favourites").get("scene").get(0).asText());

        // another user sees none of it
        assertEquals(0, profile(session()).get("episodes").asInt());
    }

    @Test
    void wrongSchemaIsRejected() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/episodes/sync").header("Authorization", "Bearer " + session())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":2,\"deviceId\":\"p\",\"episodes\":[]}")).andReturn();
        assertEquals(400, r.getResponse().getStatus());
    }

    private JsonNode sync(String token) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/episodes/sync").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode profile(String token) throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/episodes/profile").header("Authorization", "Bearer " + token)).andReturn();
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    private String session() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"phone-1\",\"bootstrap\":\"test-bootstrap-token\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}

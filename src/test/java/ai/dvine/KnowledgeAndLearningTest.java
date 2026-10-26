package ai.dvine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dvine.bootstrap-enabled=true",
        "dvine.bootstrap-token=test-bootstrap-token"
})
class KnowledgeAndLearningTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void manifestMatchesThePackItServes() throws Exception {
        MvcResult m = mvc.perform(get("/api/v1/knowledge/manifest")).andReturn();
        assertEquals(200, m.getResponse().getStatus());
        JsonNode manifest = mapper.readTree(m.getResponse().getContentAsString());
        int version = manifest.get("version").asInt();
        assertTrue(version >= 2);
        MvcResult p = mvc.perform(get(manifest.get("url").asText())).andReturn();
        assertEquals(200, p.getResponse().getStatus());
        byte[] body = p.getResponse().getContentAsByteArray();
        assertEquals(manifest.get("sha256").asText(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        assertEquals(manifest.get("size").asInt(), body.length);
        assertEquals(404, mvc.perform(get("/api/v1/knowledge/pack/999")).andReturn().getResponse().getStatus());
    }

    @Test
    void learningSyncIsAuthenticatedOwnerScopedAndIdempotent() throws Exception {
        String body = "{\"schemaVersion\":1,\"deviceId\":\"phone-1\",\"entries\":["
                + "{\"id\":\"e1\",\"kind\":\"TAUGHT_FACT\",\"text\":\"speed limit in town\",\"answer\":\"50\",\"createdAt\":1},"
                + "{\"id\":\"e2\",\"kind\":\"TAUGHT_FACT\",\"text\":\"my bank pin\",\"answer\":\"1234\",\"createdAt\":1},"
                + "{\"id\":\"e3\",\"kind\":\"BOGUS\",\"text\":\"x\",\"answer\":\"y\"}]}";
        assertEquals(401, mvc.perform(post("/api/v1/learning/sync").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getStatus());

        String a = session();
        JsonNode first = sync(a, body);
        assertEquals("e1", first.get("accepted").get(0).asText());
        assertEquals(1, first.get("accepted").size());
        assertEquals(2, first.get("rejected").size());
        JsonNode again = sync(a, body);
        assertEquals(1, again.get("accepted").size());

        String b = session();
        sync(b, body);
        Integer total = jdbc.queryForObject("select count(*) from learning_intake where entry_id = 'e1'", Integer.class);
        Integer owners = jdbc.queryForObject(
                "select count(distinct owner_user_id) from learning_intake where entry_id = 'e1'", Integer.class);
        assertEquals(2, total); // the replay by A added nothing; B got its own row
        assertEquals(2, owners); // one per owner, never shared between users
        Integer pending = jdbc.queryForObject("select count(*) from learning_intake where status <> 'PENDING'", Integer.class);
        assertEquals(0, pending);
    }

    @Test
    void wrongSchemaIsRejected() throws Exception {
        String token = session();
        MvcResult r = mvc.perform(post("/api/v1/learning/sync").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":9,\"deviceId\":\"p\",\"entries\":[]}")).andReturn();
        assertEquals(400, r.getResponse().getStatus());
    }

    private JsonNode sync(String token, String body) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/learning/sync").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
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

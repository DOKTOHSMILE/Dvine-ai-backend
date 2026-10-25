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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dvine.bootstrap-enabled=true",
        "dvine.bootstrap-token=test-bootstrap-token"
})
class TaskIsolationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void healthIsPublicAndMissingTokenIsUnauthorized() throws Exception {
        MvcResult health = mvc.perform(get("/api/v1/health")).andReturn();
        assertEquals(200, health.getResponse().getStatus());
        MvcResult denied = mvc.perform(post("/api/v1/desk-rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn();
        assertEquals(401, denied.getResponse().getStatus(), denied.getResponse().getContentAsString());
    }

    @Test
    void wrongBootstrapIsRejected() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"phone\",\"bootstrap\":\"nope\"}")).andReturn();
        assertEquals(401, result.getResponse().getStatus());
    }

    @Test
    void simpleHelloUsesOnlyObi() throws Exception {
        String token = session();
        String room = room(token);
        JsonNode task = command(token, room, "req-hello-01", "hello there", null, 201);
        List<Map<String, Object>> says = says(task.get("taskId").asText());
        assertEquals(1, says.size());
        assertEquals("obi", says.get(0).get("agent"));
        assertTrue(String.valueOf(says.get(0).get("message")).contains("constraint"));
    }

    @Test
    void callingIvieUsesOnlyIvieEvenIfObiIsOnScreen() throws Exception {
        String token = session();
        String room = room(token);
        JsonNode task = command(token, room, "req-ivie-01", "Ivie hello", "obi", 201);
        List<Map<String, Object>> says = says(task.get("taskId").asText());
        assertEquals(1, says.size());
        assertEquals("ivie", says.get(0).get("agent"));
    }

    @Test
    void activeFaceIsKeptForASimpleLine() throws Exception {
        String token = session();
        String room = room(token);
        JsonNode task = command(token, room, "req-face-01", "hello there", "ivie", 201);
        List<Map<String, Object>> says = says(task.get("taskId").asText());
        assertEquals(1, says.size());
        assertEquals("ivie", says.get(0).get("agent"));
    }

    @Test
    void complexPlanLetsBothSpeakOnceThenCloses() throws Exception {
        String token = session();
        String room = room(token);
        JsonNode task = command(token, room, "req-plan-01",
                "Should we plan a rollout and compare the softer path for the team?", null, 201);
        List<Map<String, Object>> says = says(task.get("taskId").asText());
        assertEquals(3, says.size());
        assertEquals("obi", says.get(0).get("agent"));
        assertEquals("ivie", says.get(1).get("agent"));
        assertEquals("ivie", says.get(2).get("agent"));
        assertTrue(String.valueOf(says.get(0).get("message")).contains("constraint"));
        assertTrue(String.valueOf(says.get(1).get("message")).toLowerCase().contains("softer")
                || String.valueOf(says.get(1).get("message")).toLowerCase().contains("feels"));
        assertTrue(task.get("resultText").asText().contains("Obi checked"));
        assertTrue(task.get("resultText").asText().contains("Ivie considered"));
        assertFalse(task.get("resultText").asText().contains("did not start in agreement"));
    }

    @Test
    void rushGetsOneRebuttalAndDoesNotInventAgreement() throws Exception {
        String token = session();
        String room = room(token);
        JsonNode task = command(token, room, "req-rush-01", "just do it the fastest way", null, 201);
        List<Map<String, Object>> says = says(task.get("taskId").asText());
        assertEquals(4, says.size());
        assertEquals("obi", says.get(2).get("agent"));
        assertTrue(String.valueOf(says.get(2).get("message")).contains("reversible"));
        assertTrue(task.get("resultText").asText().contains("did not start in agreement"));
    }

    @Test
    void deleteIsBlockedWithoutEchoingTheOrder() throws Exception {
        String token = session();
        String room = room(token);
        JsonNode task = command(token, room, "req-del-01", "delete the old notes", null, 201);
        String result = task.get("resultText").asText();
        assertTrue(result.contains("Nothing was sent, deleted, or approved."));
        assertFalse(result.contains("old notes"));
        List<Map<String, Object>> says = says(task.get("taskId").asText());
        assertTrue(says.stream().anyMatch(row -> "obi".equals(row.get("agent"))));
        assertTrue(says.stream().anyMatch(row -> "ivie".equals(row.get("agent"))));
        assertTrue(says.size() <= 4);
    }

    @Test
    void replayDoesNotRunTwiceAndOtherUserCannotRead() throws Exception {
        String tokenA = session();
        String tokenB = session();
        String room = room(tokenA);
        JsonNode first = command(tokenA, room, "req-once-01", "hello there", null, 201);
        JsonNode second = command(tokenA, room, "req-once-01", "hello there", null, 200);
        assertEquals(first.get("taskId").asText(), second.get("taskId").asText());
        Integer copies = jdbc.queryForObject(
                "select count(*) from tasks where idempotency_key = 'req-once-01'", Integer.class);
        assertEquals(1, copies);
        MvcResult hidden = mvc.perform(get("/api/v1/tasks/" + first.get("taskId").asText())
                .header("Authorization", "Bearer " + tokenB)).andReturn();
        assertEquals(404, hidden.getResponse().getStatus(), hidden.getResponse().getContentAsString());
        Integer denies = jdbc.queryForObject(
                "select count(*) from audit_events where decision = 'deny' and action = 'task.read'",
                Integer.class);
        assertTrue(denies != null && denies > 0);
    }

    private String session() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"phone-1\",\"bootstrap\":\"test-bootstrap-token\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return mapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    private String room(String token) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/desk-rooms")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"desk\"}")).andReturn();
        assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return mapper.readTree(result.getResponse().getContentAsString()).get("deskRoomId").asText();
    }

    private JsonNode command(String token, String room, String requestId, String text, String active, int status)
            throws Exception {
        String agent = active == null ? "" : ",\"activeAgent\":\"" + active + "\"";
        String body = "{\"requestId\":\"" + requestId + "\",\"text\":\"" + text + "\"" + agent + "}";
        MvcResult result = mvc.perform(post("/api/v1/desk-rooms/" + room + "/commands")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private List<Map<String, Object>> says(String taskId) {
        return jdbc.queryForList(
                "select agent, cue, message from task_events where task_id = ? and cue = 'SAY' order by event_id",
                taskId);
    }
}

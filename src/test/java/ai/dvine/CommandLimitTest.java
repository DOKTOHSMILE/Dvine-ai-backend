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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "dvine.bootstrap-enabled=true",
        "dvine.bootstrap-token=test-bootstrap-token",
        "dvine.commands-per-minute=3"
})
class CommandLimitTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void eachUserGetsALimitedNumberOfNewQuestionsAndReplaysAreFree() throws Exception {
        String a = session("phone-limit-a");
        String roomA = room(a);
        for (int i = 0; i < 3; i++) {
            assertEquals(201, command(a, roomA, "req-limit-a-" + i));
        }
        assertEquals(429, command(a, roomA, "req-limit-a-3"));
        // a replay returns the stored answer and runs nothing, so it is not limited
        assertEquals(200, command(a, roomA, "req-limit-a-0"));
        // another user has their own allowance
        String b = session("phone-limit-b");
        assertEquals(201, command(b, room(b), "req-limit-b-0"));
    }

    private String session(String device) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceId\":\"" + device + "\",\"bootstrap\":\"test-bootstrap-token\"}")).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private String room(String token) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/desk-rooms").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertEquals(201, r.getResponse().getStatus());
        return mapper.readTree(r.getResponse().getContentAsString()).get("deskRoomId").asText();
    }

    private int command(String token, String room, String requestId) throws Exception {
        return mvc.perform(post("/api/v1/desk-rooms/" + room + "/commands").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"text\":\"hello there\"}")).andReturn().getResponse().getStatus();
    }
}

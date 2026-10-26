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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "dvine.device-registrations-per-hour=3")
class DeviceSignInTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    private MvcResult postJson(String url, String body, String addr) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)
                .with(r -> {
                    r.setRemoteAddr(addr);
                    return r;
                })).andReturn();
    }

    @Test
    void registerSignInAndUploadLearning() throws Exception {
        MvcResult reg = postJson("/api/v1/devices/register", "{\"deviceId\":\"dev-a\"}", "10.0.0.1");
        assertEquals(201, reg.getResponse().getStatus(), reg.getResponse().getContentAsString());
        String secret = mapper.readTree(reg.getResponse().getContentAsString()).get("deviceSecret").asText();

        // the same device id cannot be registered again (no identity takeover)
        assertEquals(409, postJson("/api/v1/devices/register", "{\"deviceId\":\"dev-a\"}", "10.0.0.1")
                .getResponse().getStatus());

        assertEquals(401, postJson("/api/v1/sessions/device",
                "{\"deviceId\":\"dev-a\",\"deviceSecret\":\"wrong\"}", "10.0.0.1").getResponse().getStatus());

        MvcResult s1 = postJson("/api/v1/sessions/device",
                "{\"deviceId\":\"dev-a\",\"deviceSecret\":\"" + secret + "\"}", "10.0.0.1");
        assertEquals(200, s1.getResponse().getStatus());
        JsonNode first = mapper.readTree(s1.getResponse().getContentAsString());
        JsonNode second = mapper.readTree(postJson("/api/v1/sessions/device",
                "{\"deviceId\":\"dev-a\",\"deviceSecret\":\"" + secret + "\"}", "10.0.0.1").getResponse().getContentAsString());
        assertEquals(first.get("userId").asText(), second.get("userId").asText()); // same user every sign-in
        assertNotEquals(first.get("token").asText(), second.get("token").asText());

        MvcResult up = mvc.perform(post("/api/v1/learning/sync")
                .header("Authorization", "Bearer " + first.get("token").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schemaVersion\":1,\"deviceId\":\"dev-a\",\"entries\":[{\"id\":\"x1\",\"kind\":\"TAUGHT_FACT\","
                        + "\"text\":\"town speed limit\",\"answer\":\"50\",\"createdAt\":1}]}")).andReturn();
        assertEquals(200, up.getResponse().getStatus());
        assertEquals("x1", mapper.readTree(up.getResponse().getContentAsString()).get("accepted").get(0).asText());
    }

    @Test
    void registrationIsRateLimitedPerAddress() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(201, postJson("/api/v1/devices/register", "{\"deviceId\":\"rl-" + i + "\"}", "10.9.9.9")
                    .getResponse().getStatus());
        }
        assertEquals(429, postJson("/api/v1/devices/register", "{\"deviceId\":\"rl-9\"}", "10.9.9.9")
                .getResponse().getStatus());
        assertEquals(201, postJson("/api/v1/devices/register", "{\"deviceId\":\"rl-other\"}", "10.9.9.8")
                .getResponse().getStatus());
    }
}

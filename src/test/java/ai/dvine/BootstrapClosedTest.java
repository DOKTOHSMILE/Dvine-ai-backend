package ai.dvine;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BootstrapClosedTest {
    @Autowired
    MockMvc mvc;

    @Test
    void bootstrapIsClosedUnlessEnabled() throws Exception {
        mvc.perform(post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"phone\",\"bootstrap\":\"anything\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}

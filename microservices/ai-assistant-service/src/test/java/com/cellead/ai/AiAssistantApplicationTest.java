package com.cellead.ai;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.cellead.platform.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AiAssistantApplicationTest {
    @Autowired MockMvc mvc; @Autowired JwtService jwt;
    @Test void contextLoads() {}
    @Test void fallbackIsValidatedAndRoleProtected() throws Exception {
        String requester=jwt.generate(1L,"requester","REQUESTER");
        mvc.perform(post("/api/ai/form-assistant").header("Authorization","Bearer "+requester)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"Urgent travel to Shanghai\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestType").value("TRAVEL"))
                .andExpect(jsonPath("$.priority").value("HIGH")).andExpect(jsonPath("$.source").value("LOCAL_FALLBACK"));
        String approver=jwt.generate(2L,"approver","APPROVER");
        mvc.perform(post("/api/ai/form-assistant").header("Authorization","Bearer "+approver)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"input\":\"Travel\"}"))
                .andExpect(status().isForbidden());
    }
    @Test void runbooksAreAdminOnly() throws Exception {
        String admin=jwt.generate(3L,"admin","ADMIN");
        mvc.perform(get("/api/ai/runbooks").header("Authorization","Bearer "+admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("service-down"));
        String requester=jwt.generate(1L,"requester","REQUESTER");
        mvc.perform(get("/api/ai/runbooks").header("Authorization","Bearer "+requester))
                .andExpect(status().isForbidden());
    }
}

package com.cellead.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void completeApprovalFlowWorksThroughHttpApis() throws Exception {
        String requesterToken = login("requester", "password123");

        String workflowJson = mockMvc.perform(post("/api/workflows")
                        .header(HttpHeaders.AUTHORIZATION, bearer(requesterToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "title", "API flow request",
                                "description", "Request created by MockMvc integration test",
                                "priority", "HIGH"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestType").value("General Request"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long workflowId = objectMapper.readTree(workflowJson).get("id").asLong();

        mockMvc.perform(get("/api/workflows/my")
                        .header(HttpHeaders.AUTHORIZATION, bearer(requesterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists());

        String approverToken = login("approver", "password123");

        mockMvc.perform(get("/api/approvals/tasks")
                        .header(HttpHeaders.AUTHORIZATION, bearer(approverToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + workflowId + ")]").exists());

        mockMvc.perform(post("/api/approvals/{workflowId}/approve", workflowId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(approverToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("comment", "Approved from API integration test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVED"));

        mockMvc.perform(get("/api/workflows/{id}", workflowId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(requesterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvalRecords[0].decision").value("APPROVED"));

        mockMvc.perform(get("/api/notifications/my")
                        .header(HttpHeaders.AUTHORIZATION, bearer(requesterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.workflowId == " + workflowId + ")]").exists());

        mockMvc.perform(get("/api/audit-logs")
                        .param("workflowId", String.valueOf(workflowId))
                        .header(HttpHeaders.AUTHORIZATION, bearer(requesterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'WORKFLOW_SUBMITTED')]").exists())
                .andExpect(jsonPath("$[?(@.action == 'WORKFLOW_APPROVED')]").exists());
    }

    @Test
    void securityAndValidationErrorsAreReturnedForInvalidRequests() throws Exception {
        mockMvc.perform(get("/api/workflows/my"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "requester", "password", "wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        String requesterToken = login("requester", "password123");

        mockMvc.perform(get("/api/approvals/tasks")
                        .header(HttpHeaders.AUTHORIZATION, bearer(requesterToken)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/workflows")
                        .header(HttpHeaders.AUTHORIZATION, bearer(requesterToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("description", "Missing title", "priority", "LOW"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        String adminToken = login("admin", "password123");

        mockMvc.perform(get("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'requester')]").exists());

        mockMvc.perform(get("/api/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk());
    }

    @Test
    void invalidBearerTokenDoesNotAuthenticateRequest() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isForbidden());
    }

    private String login(String username, String password) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value(username))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode root = objectMapper.readTree(response);
        String token = root.get("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }
}

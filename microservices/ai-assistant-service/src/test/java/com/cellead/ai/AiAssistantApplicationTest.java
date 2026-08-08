package com.cellead.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cellead.platform.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AiAssistantApplicationTest {
  @Autowired MockMvc mvc;
  @Autowired JwtService jwt;
  @Autowired ObjectMapper mapper;

  private HttpServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void contextLoads() {}

  @Test
  void csrfRemainsEnabledForRequestsWithoutBearerAuthentication() throws Exception {
    mvc.perform(
            post("/api/ai/form-assistant")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\":\"Cross-site request\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void fallbackIsValidatedAndRoleProtected() throws Exception {
    String requester = jwt.generate(1L, "requester", "REQUESTER");
    mvc.perform(
            post("/api/ai/form-assistant")
                .header("Authorization", "Bearer " + requester)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\":\"Urgent travel to Shanghai\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestType").value("TRAVEL"))
        .andExpect(jsonPath("$.priority").value("HIGH"))
        .andExpect(jsonPath("$.source").value("LOCAL_FALLBACK"));
    String approver = jwt.generate(2L, "approver", "APPROVER");
    mvc.perform(
            post("/api/ai/form-assistant")
                .header("Authorization", "Bearer " + approver)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\":\"Travel\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void runbooksAreAdminOnly() throws Exception {
    String admin = jwt.generate(3L, "admin", "ADMIN");
    mvc.perform(get("/api/ai/runbooks").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("service-down"));
    String requester = jwt.generate(1L, "requester", "REQUESTER");
    mvc.perform(get("/api/ai/runbooks").header("Authorization", "Bearer " + requester))
        .andExpect(status().isForbidden());
  }

  @Test
  void onCallUsesLiveEvidenceAndRequiresAdmin() throws Exception {
    String admin = jwt.generate(3L, "admin", "ADMIN");
    mvc.perform(
            post("/api/ai/on-call")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"notification backlog\",\"correlationId\":\"trace-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.severity").value("HIGH"))
        .andExpect(jsonPath("$.source").value("LIVE_HEALTH_AND_RUNBOOK:event-backlog"))
        .andExpect(jsonPath("$.evidence[3]").value("Correlation ID: trace-1"));

    String requester = jwt.generate(1L, "requester", "REQUESTER");
    mvc.perform(
            post("/api/ai/on-call")
                .header("Authorization", "Bearer " + requester)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"service down\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void localFallbackClassifiesPurchaseAndGeneralRequests() throws Exception {
    String requester = jwt.generate(1L, "requester", "REQUESTER");
    mvc.perform(
            post("/api/ai/form-assistant")
                .header("Authorization", "Bearer " + requester)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\":\"采购办公设备\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestType").value("PURCHASE"))
        .andExpect(jsonPath("$.priority").value("MEDIUM"));

    String longInput = "A".repeat(70);
    mvc.perform(
            post("/api/ai/form-assistant")
                .header("Authorization", "Bearer " + requester)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new FormAssistRequest(longInput))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requestType").value("GENERAL"))
        .andExpect(jsonPath("$.title").value("A".repeat(60)));
  }

  @Test
  void providerParsesAndNormalizesStructuredOutput() throws Exception {
    startAiServer(
        "{\"choices\":[{\"message\":{\"content\":\"{\\\"title\\\":\\\"Trip\\\","
            + "\\\"description\\\":\\\"Book travel\\\",\\\"requestType\\\":\\\"invalid\\\","
            + "\\\"priority\\\":\\\"critical\\\",\\\"missingInformation\\\":[\\\"dates\\\"],"
            + "\\\"confidence\\\":2}\"}}]}");
    AiProvider provider =
        new AiProvider(true, serverUrl(), "test-key", "test-model", RestClient.builder(), mapper);

    FormAssistResponse response = provider.form("Book a trip");

    org.junit.jupiter.api.Assertions.assertEquals("GENERAL", response.requestType());
    org.junit.jupiter.api.Assertions.assertEquals("MEDIUM", response.priority());
    org.junit.jupiter.api.Assertions.assertEquals(1, response.confidence());
    org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("dates"), response.missingInformation());
  }

  @Test
  void providerRejectsInvalidStructuredOutput() throws Exception {
    startAiServer("{\"choices\":[{\"message\":{\"content\":\"not-json\"}}]}");
    AiProvider provider =
        new AiProvider(true, serverUrl(), "test-key", "test-model", RestClient.builder(), mapper);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class, () -> provider.form("invalid response"));
  }

  private void startAiServer(String response) throws Exception {
    server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/chat/completions",
        exchange -> {
          byte[] body = response.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
  }

  private String serverUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }
}

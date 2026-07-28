package com.cellead.workflowservice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.assertThat;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkflowServiceApplicationTest {
    @Autowired OutboxDispatcher dispatcher;
    @Autowired OutboxRepository outbox;
    @Autowired WorkflowRepository workflows;
    @Test void contextLoads() {}
    @Test void domainEventsAreDurablyQueuedBeforeDelivery() {
        dispatcher.publish(new DomainEvent("TEST_EVENT",99L,1L,"requester",2L,"test",
                java.time.Instant.now(),"test-correlation",null));
        OutboxEvent saved=outbox.findAll().stream().filter(e->"TEST_EVENT".equals(e.eventType)).findFirst().orElseThrow();
        assertThat(saved.status).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.eventId).isNotBlank();
    }
    @Test void workflowRepositorySupportsFinalStateStatistics() {
        WorkflowRequest request=new WorkflowRequest(new CreateRequest("Test","Description","GENERAL",Priority.MEDIUM,2L,"approver"),
                new com.cellead.platform.security.AuthenticatedUser(1L,"requester","REQUESTER"));
        workflows.save(request);
        assertThat(workflows.countByStatus(WorkflowStatus.PENDING)).isGreaterThanOrEqualTo(1);
        request.status=WorkflowStatus.CANCELLED;
        workflows.save(request);
        assertThat(workflows.countByStatus(WorkflowStatus.CANCELLED)).isGreaterThanOrEqualTo(1);
    }
}

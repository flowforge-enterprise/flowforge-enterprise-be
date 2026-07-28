package com.cellead.workflowservice;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cellead.platform.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class WorkflowPolicyTest {
    private final WorkflowPolicy policy = new WorkflowPolicy();
    private WorkflowRequest workflow;

    @BeforeEach
    void setUp() {
        workflow = new WorkflowRequest(new CreateRequest("Purchase laptop", "Developer laptop", "PURCHASE",
                Priority.HIGH, 22L, "approver"), user(11L, "requester", "REQUESTER"));
    }

    @Test
    void submitterApproverAndAdminCanView() {
        assertThatCode(() -> policy.authorizeView(workflow, user(11L, "requester", "REQUESTER"))).doesNotThrowAnyException();
        assertThatCode(() -> policy.authorizeView(workflow, user(22L, "approver", "APPROVER"))).doesNotThrowAnyException();
        assertThatCode(() -> policy.authorizeView(workflow, user(99L, "admin", "ADMIN"))).doesNotThrowAnyException();
    }

    @Test
    void unrelatedUserCannotView() {
        assertStatus(() -> policy.authorizeView(workflow, user(12L, "other", "REQUESTER")), HttpStatus.FORBIDDEN);
    }

    @Test
    void onlySubmitterCanCancelPendingWorkflow() {
        assertThatCode(() -> policy.authorizeCancellation(workflow, user(11L, "requester", "REQUESTER")))
                .doesNotThrowAnyException();
        assertStatus(() -> policy.authorizeCancellation(workflow, user(12L, "other", "REQUESTER")), HttpStatus.FORBIDDEN);
        assertStatus(() -> policy.authorizeCancellation(workflow, user(99L, "admin", "ADMIN")), HttpStatus.FORBIDDEN);
    }

    @Test
    void finalWorkflowCannotBeCancelled() {
        workflow.status = WorkflowStatus.APPROVED;
        assertStatus(() -> policy.authorizeCancellation(workflow, user(11L, "requester", "REQUESTER")), HttpStatus.CONFLICT);
    }

    @Test
    void onlyAssignedApproverCanDecidePendingWorkflow() {
        assertThatCode(() -> policy.authorizeDecision(workflow, user(22L, "approver", "APPROVER"))).doesNotThrowAnyException();
        assertStatus(() -> policy.authorizeDecision(workflow, user(23L, "other-approver", "APPROVER")), HttpStatus.FORBIDDEN);
        assertStatus(() -> policy.authorizeDecision(workflow, user(11L, "requester", "REQUESTER")), HttpStatus.FORBIDDEN);
    }

    @Test
    void decidedWorkflowCannotBeDecidedAgain() {
        workflow.status = WorkflowStatus.REJECTED;
        assertStatus(() -> policy.authorizeDecision(workflow, user(22L, "approver", "APPROVER")), HttpStatus.CONFLICT);
    }

    private void assertStatus(Runnable action, HttpStatus expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.getStatusCode()).isEqualTo(expected));
    }

    private AuthenticatedUser user(long id, String username, String role) {
        return new AuthenticatedUser(id, username, role);
    }
}

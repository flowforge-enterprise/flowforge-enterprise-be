package com.cellead.workflowservice;

import com.cellead.platform.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class WorkflowPolicy {
  void requireRole(AuthenticatedUser user, String role) {
    if (user == null || !role.equals(user.role())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Role " + role + " is required");
    }
  }

  void authorizeView(WorkflowRequest workflow, AuthenticatedUser user) {
    if (user == null
        || !("ADMIN".equals(user.role())
            || workflow.submitterId.equals(user.id())
            || workflow.approverId.equals(user.id()))) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Workflow is not visible to this user");
    }
  }

  void authorizeCancellation(WorkflowRequest workflow, AuthenticatedUser user) {
    requireRole(user, "REQUESTER");
    if (!workflow.submitterId.equals(user.id())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only the submitter can cancel this workflow");
    }
    requirePending(workflow, "Only pending workflows can be cancelled");
  }

  void authorizeDecision(WorkflowRequest workflow, AuthenticatedUser user) {
    requireRole(user, "APPROVER");
    if (!workflow.approverId.equals(user.id())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Task is not assigned to this approver");
    }
    requirePending(workflow, "Workflow has already reached a final state");
  }

  private void requirePending(WorkflowRequest workflow, String message) {
    if (workflow.status != WorkflowStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
  }
}

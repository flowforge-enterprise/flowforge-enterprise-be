package com.cellead.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class UnitModelAndSecurityTest {
    @Test
    void dtoAndEntityFactoryMethodsExposeExpectedValues() {
        AppUser requester = new AppUser("requester-unit", "hash", Role.REQUESTER);
        AppUser approver = new AppUser("approver-unit", "hash", Role.APPROVER);
        WorkflowRequest workflow = new WorkflowRequest("Title", "Description", "Type", Priority.HIGH, requester, approver);
        ApprovalRecord approval = new ApprovalRecord(workflow, approver, Decision.APPROVED, "Looks good");
        AuditLog auditLog = new AuditLog(AuditAction.USER_LOGIN, requester, null, "Login");
        NotificationRecord notification = new NotificationRecord(
                workflow,
                requester,
                NotificationChannel.WEBSOCKET,
                "Message",
                NotificationStatus.SENT
        );

        assertThat(new CreateWorkflowRequestDto("T", "D", null, Priority.LOW, null).normalizedRequestType())
                .isEqualTo("General Request");
        assertThat(new CreateWorkflowRequestDto("T", "D", "  Custom  ", Priority.LOW, null).normalizedRequestType())
                .isEqualTo("Custom");
        assertThat(UserSummary.from(requester).username()).isEqualTo("requester-unit");
        assertThat(WorkflowResponse.summary(workflow).approvalRecords()).isEmpty();
        assertThat(ApprovalRecordResponse.from(approval).decision()).isEqualTo(Decision.APPROVED);
        assertThat(AuditLogResponse.from(auditLog).workflowId()).isNull();
        assertThat(NotificationRecordResponse.from(notification).status()).isEqualTo(NotificationStatus.SENT);
        assertThat(ErrorResponse.of("ERR", "Message").timestamp()).isNotNull();

        workflow.markApproved();
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.APPROVED);
        workflow.markRejected();
        assertThat(workflow.getStatus()).isEqualTo(WorkflowStatus.REJECTED);
    }

    @Test
    void jwtServiceGeneratesAndValidatesTokens() {
        JwtService jwtService = new JwtService("unit-test-secret-key-with-at-least-32-characters", 60);
        AppUser user = new AppUser("jwt-user", "hash", Role.ADMIN);
        String token = jwtService.generateToken(user);

        assertThat(jwtService.extractUsername(token)).isEqualTo("jwt-user");
        assertThat(jwtService.isTokenValid(token, org.springframework.security.core.userdetails.User
                .withUsername("jwt-user")
                .password("hash")
                .roles("ADMIN")
                .build())).isTrue();
        assertThat(jwtService.isTokenValid(token, org.springframework.security.core.userdetails.User
                .withUsername("someone-else")
                .password("hash")
                .roles("ADMIN")
                .build())).isFalse();
    }

    @Test
    void customUserDetailsServiceLoadsRoleAndRejectsUnknownUser() {
        UserRepository users = mock(UserRepository.class);
        when(users.findByUsername("approver")).thenReturn(Optional.of(new AppUser("approver", "hash", Role.APPROVER)));
        when(users.findByUsername("missing")).thenReturn(Optional.empty());

        CustomUserDetailsService service = new CustomUserDetailsService(users);

        assertThat(service.loadUserByUsername("approver").getAuthorities())
                .extracting(Object::toString)
                .contains("ROLE_APPROVER");
        assertThatThrownBy(() -> service.loadUserByUsername("missing"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void notificationServiceRecordsFailureWhenWebSocketSendFails() {
        NotificationRecordRepository records = mock(NotificationRecordRepository.class);
        SimpMessageSendingOperations messagingTemplate = mock(SimpMessageSendingOperations.class);
        NotificationService service = new NotificationService(records, messagingTemplate);
        AppUser requester = new AppUser("requester-notification", "hash", Role.REQUESTER);
        AppUser approver = new AppUser("approver-notification", "hash", Role.APPROVER);
        WorkflowRequest workflow = new WorkflowRequest("Title", "Description", "General Request", Priority.LOW, requester, approver);

        doThrow(new IllegalStateException("broker unavailable")).when(messagingTemplate).convertAndSend(any(String.class), any(Object.class));
        when(records.save(any(NotificationRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationRecord saved = service.notify(requester, workflow, "Message");

        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(saved.getChannel()).isEqualTo(NotificationChannel.WEBSOCKET);
        verify(records).save(any(NotificationRecord.class));
    }
}

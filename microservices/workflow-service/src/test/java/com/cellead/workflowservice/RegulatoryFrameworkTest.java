package com.cellead.workflowservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cellead.platform.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class RegulatoryFrameworkTest {
  private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin", "ADMIN");
  private static final AuthenticatedUser REQUESTER =
      new AuthenticatedUser(2L, "requester", "REQUESTER");

  @Test
  void listsFrameworkAndUpdatesControlForAdmin() {
    RegulatoryFrameworkRepository frameworks = mock(RegulatoryFrameworkRepository.class);
    RegulatoryControlRepository controls = mock(RegulatoryControlRepository.class);
    RegulatoryFrameworkEntity framework = framework();
    RegulatoryControl control = control();
    when(frameworks.findAll()).thenReturn(List.of(framework));
    when(frameworks.findByCode("BASIC-SECURITY")).thenReturn(Optional.of(framework));
    when(controls.findById(10L)).thenReturn(Optional.of(control));
    when(controls.findByFrameworkIdOrderByControlCode(1L)).thenReturn(List.of(control));
    RegulatoryFrameworkService service = new RegulatoryFrameworkService(frameworks, controls);
    RegulatoryFrameworkController controller =
        new RegulatoryFrameworkController(service, new WorkflowPolicy());

    assertThat(controller.list()).singleElement().satisfies(item -> assertThat(item.total()).isOne());
    RegulatoryFrameworkResponse response =
        controller.update(
            "BASIC-SECURITY",
            10L,
            new ControlUpdateRequest(ControlStatus.COMPLIANT, "CI evidence #123"),
            ADMIN);

    assertThat(response.compliant()).isOne();
    assertThat(response.controls().get(0).evidenceNote()).isEqualTo("CI evidence #123");
    assertThat(response.controls().get(0).updatedBy()).isEqualTo("admin");
  }

  @Test
  void rejectsControlUpdateForNonAdmin() {
    RegulatoryFrameworkController controller =
        new RegulatoryFrameworkController(
            mock(RegulatoryFrameworkService.class), new WorkflowPolicy());

    assertThatThrownBy(
            () ->
                controller.update(
                    "BASIC-SECURITY",
                    10L,
                    new ControlUpdateRequest(ControlStatus.COMPLIANT, null),
                    REQUESTER))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void internalEvidenceEndpointRequiresKeyAndUpdatesByControlCode() {
    RegulatoryFrameworkService service = mock(RegulatoryFrameworkService.class);
    RegulatoryEvidenceController controller =
        new RegulatoryEvidenceController(service, "internal-key");
    ControlUpdateRequest request =
        new ControlUpdateRequest(ControlStatus.COMPLIANT, "GitHub Actions run");

    assertThatThrownBy(
            () -> controller.update("BASIC-SECURITY", "CI-01", request, "wrong-key"))
        .isInstanceOf(ResponseStatusException.class);
    controller.update("BASIC-SECURITY", "CI-01", request, "internal-key");
    org.mockito.Mockito.verify(service)
        .updateByCode(
            org.mockito.ArgumentMatchers.eq("BASIC-SECURITY"),
            org.mockito.ArgumentMatchers.eq("CI-01"),
            org.mockito.ArgumentMatchers.eq(request),
            org.mockito.ArgumentMatchers.argThat(user -> "github-actions".equals(user.username())));
  }

  private RegulatoryFrameworkEntity framework() {
    RegulatoryFrameworkEntity framework = new RegulatoryFrameworkEntity();
    framework.id = 1L;
    framework.code = "BASIC-SECURITY";
    framework.name = "Basic Security Framework";
    framework.description = "Baseline";
    framework.createdAt = Instant.now();
    return framework;
  }

  private RegulatoryControl control() {
    RegulatoryControl control = new RegulatoryControl();
    control.id = 10L;
    control.frameworkId = 1L;
    control.controlCode = "AC-01";
    control.title = "Access control";
    control.description = "JWT and RBAC";
    control.status = ControlStatus.IN_PROGRESS;
    control.updatedAt = Instant.now();
    return control;
  }
}

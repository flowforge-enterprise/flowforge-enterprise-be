package com.cellead.workflowservice;

import com.cellead.platform.security.AuthenticatedUser;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

enum ControlStatus {
  NOT_STARTED,
  IN_PROGRESS,
  COMPLIANT,
  NOT_APPLICABLE
}

@Entity
@Table(name = "regulatory_frameworks")
class RegulatoryFrameworkEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false, unique = true)
  String code;

  @Column(nullable = false)
  String name;

  @Column(nullable = false, length = 1000)
  String description;

  @Column(nullable = false)
  Instant createdAt;

  protected RegulatoryFrameworkEntity() {}
}

@Entity
@Table(name = "regulatory_controls")
class RegulatoryControl {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  @Column(nullable = false)
  Long frameworkId;

  @Column(nullable = false)
  String controlCode;

  @Column(nullable = false)
  String title;

  @Column(nullable = false, length = 1000)
  String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  ControlStatus status;

  @Column(length = 2000)
  String evidenceNote;

  String updatedBy;

  @Column(nullable = false)
  Instant updatedAt;

  protected RegulatoryControl() {}

  void update(ControlUpdateRequest request, AuthenticatedUser user) {
    status = request.status();
    evidenceNote = normalize(request.evidenceNote());
    updatedBy = user.username();
    updatedAt = Instant.now();
  }

  private String normalize(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}

interface RegulatoryFrameworkRepository extends JpaRepository<RegulatoryFrameworkEntity, Long> {
  java.util.Optional<RegulatoryFrameworkEntity> findByCode(String code);
}

interface RegulatoryControlRepository extends JpaRepository<RegulatoryControl, Long> {
  List<RegulatoryControl> findByFrameworkIdOrderByControlCode(Long frameworkId);

  java.util.Optional<RegulatoryControl> findByFrameworkIdAndControlCode(
      Long frameworkId, String controlCode);
}

record ControlUpdateRequest(
    @jakarta.validation.constraints.NotNull ControlStatus status,
    @Size(max = 2000) String evidenceNote) {}

record RegulatoryControlResponse(
    Long id,
    String code,
    String title,
    String description,
    ControlStatus status,
    String evidenceNote,
    String updatedBy,
    Instant updatedAt) {
  static RegulatoryControlResponse from(RegulatoryControl control) {
    return new RegulatoryControlResponse(
        control.id,
        control.controlCode,
        control.title,
        control.description,
        control.status,
        control.evidenceNote,
        control.updatedBy,
        control.updatedAt);
  }
}

record RegulatoryFrameworkSummary(
    String code, String name, String description, long compliant, long total) {}

record RegulatoryFrameworkResponse(
    String code,
    String name,
    String description,
    long compliant,
    long total,
    List<RegulatoryControlResponse> controls) {}

@Service
class RegulatoryFrameworkService {
  private final RegulatoryFrameworkRepository frameworks;
  private final RegulatoryControlRepository controls;

  RegulatoryFrameworkService(
      RegulatoryFrameworkRepository frameworks, RegulatoryControlRepository controls) {
    this.frameworks = frameworks;
    this.controls = controls;
  }

  @Transactional(readOnly = true)
  List<RegulatoryFrameworkSummary> list() {
    return frameworks.findAll().stream()
        .map(
            framework -> {
              List<RegulatoryControl> items = controls.findByFrameworkIdOrderByControlCode(framework.id);
              return new RegulatoryFrameworkSummary(
                  framework.code,
                  framework.name,
                  framework.description,
                  compliantCount(items),
                  items.size());
            })
        .toList();
  }

  @Transactional(readOnly = true)
  RegulatoryFrameworkResponse get(String code) {
    RegulatoryFrameworkEntity framework = findFramework(code);
    List<RegulatoryControl> items = controls.findByFrameworkIdOrderByControlCode(framework.id);
    return response(framework, items);
  }

  @Transactional
  RegulatoryFrameworkResponse update(
      String code, Long controlId, ControlUpdateRequest request, AuthenticatedUser user) {
    RegulatoryFrameworkEntity framework = findFramework(code);
    RegulatoryControl control =
        controls
            .findById(controlId)
            .filter(item -> framework.id.equals(item.frameworkId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    control.update(request, user);
    controls.save(control);
    return response(framework, controls.findByFrameworkIdOrderByControlCode(framework.id));
  }

  @Transactional
  RegulatoryFrameworkResponse updateByCode(
      String frameworkCode,
      String controlCode,
      ControlUpdateRequest request,
      AuthenticatedUser actor) {
    RegulatoryFrameworkEntity framework = findFramework(frameworkCode);
    RegulatoryControl control =
        controls
            .findByFrameworkIdAndControlCode(framework.id, controlCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    control.update(request, actor);
    controls.save(control);
    return response(framework, controls.findByFrameworkIdOrderByControlCode(framework.id));
  }

  private RegulatoryFrameworkEntity findFramework(String code) {
    return frameworks
        .findByCode(code)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  private RegulatoryFrameworkResponse response(
      RegulatoryFrameworkEntity framework, List<RegulatoryControl> items) {
    return new RegulatoryFrameworkResponse(
        framework.code,
        framework.name,
        framework.description,
        compliantCount(items),
        items.size(),
        items.stream().map(RegulatoryControlResponse::from).toList());
  }

  private long compliantCount(List<RegulatoryControl> controls) {
    return controls.stream().filter(item -> item.status == ControlStatus.COMPLIANT).count();
  }
}

@RestController
@RequestMapping("/api/regulatory/frameworks")
class RegulatoryFrameworkController {
  private final RegulatoryFrameworkService service;
  private final WorkflowPolicy policy;

  RegulatoryFrameworkController(RegulatoryFrameworkService service, WorkflowPolicy policy) {
    this.service = service;
    this.policy = policy;
  }

  @GetMapping
  List<RegulatoryFrameworkSummary> list() {
    return service.list();
  }

  @GetMapping("/{code}")
  RegulatoryFrameworkResponse get(@PathVariable String code) {
    return service.get(code);
  }

  @PatchMapping("/{code}/controls/{controlId}")
  RegulatoryFrameworkResponse update(
      @PathVariable String code,
      @PathVariable Long controlId,
      @Valid @RequestBody ControlUpdateRequest request,
      @AuthenticationPrincipal AuthenticatedUser user) {
    policy.requireRole(user, "ADMIN");
    return service.update(code, controlId, request, user);
  }
}

@RestController
@RequestMapping("/internal/regulatory/frameworks")
class RegulatoryEvidenceController {
  private static final AuthenticatedUser GITHUB_ACTIONS =
      new AuthenticatedUser(0L, "github-actions", "SYSTEM");

  private final RegulatoryFrameworkService service;
  private final String internalKey;

  RegulatoryEvidenceController(
      RegulatoryFrameworkService service,
      @org.springframework.beans.factory.annotation.Value("${app.internal-key}")
          String internalKey) {
    this.service = service;
    this.internalKey = internalKey;
  }

  @PatchMapping("/{frameworkCode}/controls/{controlCode}")
  RegulatoryFrameworkResponse update(
      @PathVariable String frameworkCode,
      @PathVariable String controlCode,
      @Valid @RequestBody ControlUpdateRequest request,
      @RequestHeader("X-Internal-Key") String suppliedKey) {
    if (!internalKey.equals(suppliedKey)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    return service.updateByCode(frameworkCode, controlCode, request, GITHUB_ACTIONS);
  }
}

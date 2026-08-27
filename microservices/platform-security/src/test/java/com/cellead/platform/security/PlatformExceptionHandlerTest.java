package com.cellead.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

class PlatformExceptionHandlerTest {
  private final PlatformExceptionHandler handler = new PlatformExceptionHandler();

  @Test
  void validationUsesFieldMessageAndHasSafeFallback() {
    BeanPropertyBindingResult errors = new BeanPropertyBindingResult(new Object(), "request");
    errors.addError(new FieldError("request", "title", "must not be blank"));
    MethodParameter parameter = mock(MethodParameter.class);
    MethodArgumentNotValidException exception =
        new MethodArgumentNotValidException(parameter, errors);

    var response = handler.validation(exception);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody().message()).isEqualTo("title: must not be blank");

    var fallback =
        handler.validation(
            new MethodArgumentNotValidException(
                parameter, new BeanPropertyBindingResult(new Object(), "request")));
    assertThat(fallback.getBody().message()).isEqualTo("Validation failed");
  }

  @Test
  void statusAndBadRequestPreserveUsefulMessages() {
    var status = handler.status(new ResponseStatusException(HttpStatus.NOT_FOUND, "missing"));
    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(status.getBody().error()).isEqualTo("NOT_FOUND");
    assertThat(status.getBody().message()).isEqualTo("missing");

    var badRequest = handler.badRequest(new IllegalArgumentException("invalid value"));
    assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(badRequest.getBody().message()).isEqualTo("invalid value");
  }

  @Test
  void malformedConflictAndDependencyFailuresUseStableContracts() {
    var malformed = handler.malformed(mock(HttpMessageNotReadableException.class));
    assertThat(malformed.getBody().message()).isEqualTo("Malformed or unsupported request body");

    var conflict = handler.conflict(new DataIntegrityViolationException("duplicate"));
    assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(conflict.getBody().error()).isEqualTo("CONFLICT");

    var dependency = handler.dependency(new RestClientException("down"));
    assertThat(dependency.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(dependency.getBody().error()).isEqualTo("SERVICE_UNAVAILABLE");
  }

  @Test
  void unexpectedFailureDoesNotExposeInternalDetails() {
    var response = handler.unexpected(new IllegalStateException("secret detail"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
  }
}

package com.cellead.workflow;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

class ResourceNotFoundException extends RuntimeException {
    ResourceNotFoundException(String message) {
        super(message);
    }
}

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception ex) {
        String message = ex instanceof MethodArgumentNotValidException validationException
                ? validationException.getBindingResult().getAllErrors().get(0).getDefaultMessage()
                : ex.getMessage();
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("BAD_REQUEST", message));
    }

    @ExceptionHandler({AccessDeniedException.class})
    ResponseEntity<ErrorResponse> forbidden(Exception ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(BadCredentialsException.class)
    ResponseEntity<ErrorResponse> badCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ErrorResponse.of("UNAUTHORIZED", "Invalid username or password"));
    }
}

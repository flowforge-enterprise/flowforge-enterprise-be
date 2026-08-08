package com.cellead.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;

public final class SecurityJsonHandlers {
  private SecurityJsonHandlers() {}

  public static void configure(
      ExceptionHandlingConfigurer<
              org.springframework.security.config.annotation.web.builders.HttpSecurity>
          handling,
      ObjectMapper mapper) {
    handling
        .authenticationEntryPoint(
            (request, response, exception) ->
                write(response, mapper, 401, "UNAUTHORIZED", "Authentication is required"))
        .accessDeniedHandler(
            (request, response, exception) ->
                write(response, mapper, 403, "FORBIDDEN", "Access is denied"));
  }

  private static void write(
      HttpServletResponse response, ObjectMapper mapper, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), ApiError.of(code, message));
  }
}

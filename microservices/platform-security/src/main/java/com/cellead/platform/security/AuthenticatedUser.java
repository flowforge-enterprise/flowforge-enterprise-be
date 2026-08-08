package com.cellead.platform.security;

import java.security.Principal;

public record AuthenticatedUser(Long id, String username, String role) implements Principal {
  @Override
  public String getName() {
    return username;
  }
}

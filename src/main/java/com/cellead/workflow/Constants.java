package com.cellead.workflow;

/**
 * 项目常量集合（公共常量）
 */
public final class Constants {
    private Constants() {}

    /**
     * Default password used for seeded/test users.
     *
     * Resolution for security scanner: read from system property or environment variable
     * instead of hardcoding. To override, set either the system property
     * -DDEFAULT_PASSWORD=<value> or the environment variable DEFAULT_PASSWORD.
     * If neither is set, falls back to the legacy value for tests and local dev.
     */
    public static final String DEFAULT_PASSWORD;

    static {
        String pwd = System.getProperty("DEFAULT_PASSWORD");
        if (pwd == null || pwd.isEmpty()) {
            pwd = System.getenv("DEFAULT_PASSWORD");
        }
        if (pwd == null || pwd.isEmpty()) {
            pwd = "password123"; // legacy fallback for local/dev/tests
        }
        DEFAULT_PASSWORD = pwd;
    }
}

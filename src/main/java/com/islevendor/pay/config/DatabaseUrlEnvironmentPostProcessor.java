package com.islevendor.pay.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Rewrites spring.datasource.url into JDBC form before anything uses it (see
 * {@link DatabaseUrl}). Credentials in the URL are used only when the
 * username and password aren't set on their own; explicit ones always win,
 * so pasting the marketplace's own URL can't make this service log in as
 * the marketplace (and {@link DatabaseRoleCheck} refuses a superuser anyway).
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty("spring.datasource.url");
        if (raw == null) {
            return;
        }
        DatabaseUrl parsed = DatabaseUrl.parse(raw);
        Map<String, Object> overrides = new HashMap<>();
        overrides.put("spring.datasource.url", parsed.jdbcUrl());
        String username = environment.getProperty("spring.datasource.username", "");
        if (username.isBlank() && parsed.username() != null) {
            overrides.put("spring.datasource.username", parsed.username());
            if (parsed.password() != null) {
                overrides.put("spring.datasource.password", parsed.password());
            }
        }
        environment.getPropertySources().addFirst(new MapPropertySource("islevendorPayDatabaseUrl", overrides));
    }
}

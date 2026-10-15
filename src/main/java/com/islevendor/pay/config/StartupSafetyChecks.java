package com.islevendor.pay.config;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Refuses to start with an unsafe configuration, the same way the Node
 * backend's env.ts does: better a service that won't boot than one that
 * talks to the wrong database or the live gateway by accident.
 */
public final class StartupSafetyChecks {

    static final int MIN_SECRET_LENGTH = 32;
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");

    private StartupSafetyChecks() {
    }

    public static void verify(PayProperties props, String datasourceUrl) {
        List<String> problems = new ArrayList<>();

        if (props.marketplaceJwtSecret().isBlank()) {
            problems.add("JWT_SECRET is required to verify marketplace logins");
        }
        if (props.enabled() && props.serviceToken().length() < MIN_SECRET_LENGTH) {
            problems.add("ISLEVENDOR_PAY_SERVICE_TOKEN must be at least " + MIN_SECRET_LENGTH
                    + " characters when ISLEVENDOR_PAY_ENABLED=true");
        }
        String env = props.wipay().environment();
        if (!Set.of("sandbox", "live", "production").contains(env)) {
            problems.add("WIPAY_ENV must be sandbox, live or production (got \"" + env + "\")");
        }
        if (props.wipay().isLive() && !props.wipay().liveAuthorized()) {
            problems.add("WIPAY_ENV is live but ISLEVENDOR_PAY_WIPAY_LIVE_AUTHORIZED is not true; "
                    + "keep the sandbox until WiPay confirms the account in writing");
        }
        if (!props.allowRemoteDatabase() && !isLocalDatabase(datasourceUrl)) {
            problems.add("ISLEVENDOR_PAY_DATABASE_URL points at a non-local database; "
                    + "set ISLEVENDOR_PAY_ALLOW_REMOTE_DATABASE=true only where that is intended");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("IsleVendor Pay refused to start:\n - " + String.join("\n - ", problems));
        }
    }

    static boolean isLocalDatabase(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return false;
        }
        try {
            String host = URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
            return host != null && LOCAL_HOSTS.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

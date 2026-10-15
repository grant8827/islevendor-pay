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
        PayProperties.WiPay wipay = props.wipay();

        if (props.marketplaceJwtSecret().isBlank()) {
            problems.add("JWT_SECRET is required to verify marketplace logins");
        }
        String env = wipay.environment();
        if (!Set.of("sandbox", "live", "production").contains(env)) {
            problems.add("WIPAY_ENV must be sandbox, live or production (got \"" + env + "\")");
        }
        if (wipay.isLive() && !wipay.liveAuthorized()) {
            problems.add("WIPAY_ENV is live but ISLEVENDOR_PAY_WIPAY_LIVE_AUTHORIZED is not true; "
                    + "keep the sandbox until WiPay confirms the account in writing");
        }
        if (wipay.isLive() && (wipay.accountNumber().isEmpty() || wipay.apiKey().isEmpty())) {
            problems.add("WIPAY_ACCOUNT_NUMBER and WIPAY_API_KEY are required for live WiPay");
        }
        if (!wipay.origin().matches("[A-Za-z0-9_-]{1,32}")) {
            problems.add("WIPAY_ORIGIN must be 1-32 letters, digits, '_' or '-'");
        }
        if (!wipay.testBaseUrl().isEmpty() && !isLoopbackHttpUrl(wipay.testBaseUrl())) {
            problems.add("islevendor.pay.wipay.test-base-url may only point at this machine");
        }
        if (!props.allowRemoteDatabase() && !isLocalDatabase(datasourceUrl)) {
            problems.add("ISLEVENDOR_PAY_DATABASE_URL points at a non-local database; "
                    + "set ISLEVENDOR_PAY_ALLOW_REMOTE_DATABASE=true only where that is intended");
        }

        if (props.enabled()) {
            if (props.serviceToken().length() < MIN_SECRET_LENGTH) {
                problems.add("ISLEVENDOR_PAY_SERVICE_TOKEN must be at least " + MIN_SECRET_LENGTH
                        + " characters when ISLEVENDOR_PAY_ENABLED=true");
            }
            // Customers are sent to and from these, so they must be https (http only on this machine).
            if (!isHttpsOrLoopback(props.publicBaseUrl())) {
                problems.add("ISLEVENDOR_PAY_PUBLIC_URL (this service's public https address) is required");
            }
            if (!isHttpsOrLoopback(props.marketplaceWebUrl())) {
                problems.add("PUBLIC_WEB_URL (the marketplace web app's https address) is required");
            }
            if (!isHttpUrl(props.marketplaceInternalUrl())) {
                problems.add("MARKETPLACE_INTERNAL_URL (the Node backend's private address) is required");
            }
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

    private static URI parse(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null || uri.getRawUserInfo() != null ? null : uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isHttpUrl(String url) {
        URI uri = parse(url);
        return uri != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()));
    }

    private static boolean isLoopbackHttpUrl(String url) {
        URI uri = parse(url);
        return uri != null && isHttpUrl(url) && LOCAL_HOSTS.contains(uri.getHost().toLowerCase());
    }

    private static boolean isHttpsOrLoopback(String url) {
        URI uri = parse(url);
        return uri != null && ("https".equals(uri.getScheme()) || isLoopbackHttpUrl(url));
    }
}

package com.islevendor.pay.gateway;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * The only places a customer may be sent to pay. Fixed in code, not
 * configurable from the Superadmin dashboard: an admin setting (or a
 * compromised admin account) must never be able to point checkout at
 * another site.
 */
public final class GatewayUrlPolicy {

    public static final String WIPAY_SANDBOX_HOST = "jmsb.wipayfinancial.com";
    public static final String WIPAY_LIVE_HOST = "jm.wipayfinancial.com";

    private GatewayUrlPolicy() {
    }

    public static String wipayHost(GatewayEnvironment environment) {
        return environment == GatewayEnvironment.LIVE ? WIPAY_LIVE_HOST : WIPAY_SANDBOX_HOST;
    }

    /**
     * Accepts a checkout URL returned by WiPay only if it is https, on the
     * WiPay host for this environment, on the default port and without
     * embedded credentials. Returns the parsed URI, or empty if refused.
     */
    public static Optional<URI> safeWiPayCheckoutUrl(String candidate, GatewayEnvironment environment) {
        if (candidate == null || candidate.length() > 2048) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        boolean ok = "https".equals(uri.getScheme())
                && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443)
                && uri.getHost() != null
                && uri.getHost().toLowerCase(Locale.ROOT).equals(wipayHost(environment));
        return ok ? Optional.of(uri) : Optional.empty();
    }
}

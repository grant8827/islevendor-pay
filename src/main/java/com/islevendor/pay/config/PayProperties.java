package com.islevendor.pay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code islevendor.pay.*}; see application.yml for the environment variables. */
@ConfigurationProperties(prefix = "islevendor.pay")
public record PayProperties(
        boolean enabled,
        String marketplaceJwtSecret,
        String serviceToken,
        boolean allowRemoteDatabase,
        String publicBaseUrl,
        String marketplaceWebUrl,
        String marketplaceInternalUrl,
        WiPay wipay) {

    public PayProperties {
        marketplaceJwtSecret = orEmpty(marketplaceJwtSecret);
        serviceToken = orEmpty(serviceToken);
        publicBaseUrl = trimSlash(publicBaseUrl);
        marketplaceWebUrl = trimSlash(marketplaceWebUrl);
        marketplaceInternalUrl = trimSlash(marketplaceInternalUrl);
        wipay = wipay == null ? new WiPay("sandbox", false, null, null, null, null, null) : wipay;
    }

    /** WiPay's published sandbox account; never used outside the sandbox. */
    static final String SANDBOX_ACCOUNT_NUMBER = "1234567890";
    static final String SANDBOX_API_KEY = "123";

    public record WiPay(
            String environment,
            boolean liveAuthorized,
            String accountNumber,
            String apiKey,
            String paymentsWebhookSecret,
            String origin,
            // Tests only: a local fake WiPay. Refused at start-up unless it is a loopback address.
            String testBaseUrl) {

        public WiPay {
            environment = environment == null || environment.isBlank() ? "sandbox" : environment.trim().toLowerCase();
            accountNumber = orEmpty(accountNumber);
            apiKey = orEmpty(apiKey);
            paymentsWebhookSecret = orEmpty(paymentsWebhookSecret);
            origin = origin == null || origin.isBlank() ? "IsleVendor" : origin.trim();
            testBaseUrl = trimSlash(testBaseUrl);
        }

        /** WiPay calls it "live"; the Node backend also accepts "production". */
        public boolean isLive() {
            return environment.equals("live") || environment.equals("production");
        }

        public String effectiveAccountNumber() {
            return !accountNumber.isEmpty() || isLive() ? accountNumber : SANDBOX_ACCOUNT_NUMBER;
        }

        public String effectiveApiKey() {
            return !apiKey.isEmpty() || isLive() ? apiKey : SANDBOX_API_KEY;
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String trimSlash(String s) {
        String v = orEmpty(s);
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}

package com.islevendor.pay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code islevendor.pay.*}; see application.yml for the environment variables. */
@ConfigurationProperties(prefix = "islevendor.pay")
public record PayProperties(
        boolean enabled,
        String marketplaceJwtSecret,
        String serviceToken,
        boolean allowRemoteDatabase,
        WiPay wipay) {

    public PayProperties {
        marketplaceJwtSecret = marketplaceJwtSecret == null ? "" : marketplaceJwtSecret;
        serviceToken = serviceToken == null ? "" : serviceToken;
        wipay = wipay == null ? new WiPay("sandbox", false) : wipay;
    }

    public record WiPay(String environment, boolean liveAuthorized) {

        public WiPay {
            environment = environment == null || environment.isBlank() ? "sandbox" : environment.trim().toLowerCase();
        }

        /** WiPay calls it "live"; the Node backend also accepts "production". */
        public boolean isLive() {
            return environment.equals("live") || environment.equals("production");
        }
    }
}

package com.islevendor.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.islevendor.pay.config.PayProperties;
import com.islevendor.pay.config.StartupSafetyChecks;
import com.islevendor.pay.gateway.GatewayEnvironment;
import com.islevendor.pay.gateway.GatewayUrlPolicy;
import org.junit.jupiter.api.Test;

/** No Spring, no database: the start-up guards and the checkout URL allowlist. */
class SafetyRulesTest {

    private static final String LOCAL_DB = "jdbc:postgresql://localhost:5432/islevendor_pay_dev";
    private static final String TOKEN = "x".repeat(32);

    private static PayProperties props(boolean enabled, String serviceToken, boolean remoteOk, String env, boolean live) {
        return new PayProperties(enabled, "jwt-secret", serviceToken, remoteOk,
                "https://pay.islevendor.com", "https://islevendor.com", "http://backend.railway.internal:4000",
                new PayProperties.WiPay(env, live, "acct", "key", null, null, null));
    }

    @Test
    void startsWithSafeLocalDefaults() {
        assertThatCode(() -> StartupSafetyChecks.verify(props(false, "", false, "sandbox", false), LOCAL_DB))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesLiveWiPayUntilAuthorised() {
        assertThatThrownBy(() -> StartupSafetyChecks.verify(props(false, "", false, "live", false), LOCAL_DB))
                .hasMessageContaining("ISLEVENDOR_PAY_WIPAY_LIVE_AUTHORIZED");
        assertThatThrownBy(() -> StartupSafetyChecks.verify(props(false, "", false, "production", false), LOCAL_DB))
                .hasMessageContaining("ISLEVENDOR_PAY_WIPAY_LIVE_AUTHORIZED");
        assertThatThrownBy(() -> StartupSafetyChecks.verify(props(false, "", false, "staging", false), LOCAL_DB))
                .hasMessageContaining("WIPAY_ENV");
    }

    @Test
    void refusesARemoteDatabaseUnlessAllowed() {
        String remote = "jdbc:postgresql://postgres.railway.internal:5432/islevendor";
        assertThatThrownBy(() -> StartupSafetyChecks.verify(props(false, "", false, "sandbox", false), remote))
                .hasMessageContaining("non-local database");
        assertThatCode(() -> StartupSafetyChecks.verify(props(false, "", true, "sandbox", false), remote))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresAStrongServiceTokenWhenSwitchedOn() {
        assertThatThrownBy(() -> StartupSafetyChecks.verify(props(true, "short", false, "sandbox", false), LOCAL_DB))
                .hasMessageContaining("ISLEVENDOR_PAY_SERVICE_TOKEN");
        assertThatCode(() -> StartupSafetyChecks.verify(props(true, TOKEN, false, "sandbox", false), LOCAL_DB))
                .doesNotThrowAnyException();
    }

    @Test
    void requiresTheMarketplaceJwtSecret() {
        var noSecret = new PayProperties(false, "", "", false, null, null, null, null);
        assertThatThrownBy(() -> StartupSafetyChecks.verify(noSecret, LOCAL_DB)).hasMessageContaining("JWT_SECRET");
    }

    @Test
    void switchedOnNeedsHttpsAddressesForCustomersAndTheMarketplace() {
        var noUrls = new PayProperties(true, "jwt", TOKEN, false, null, null, null, null);
        assertThatThrownBy(() -> StartupSafetyChecks.verify(noUrls, LOCAL_DB))
                .hasMessageContaining("ISLEVENDOR_PAY_PUBLIC_URL")
                .hasMessageContaining("PUBLIC_WEB_URL")
                .hasMessageContaining("MARKETPLACE_INTERNAL_URL");
        var plainHttp = new PayProperties(true, "jwt", TOKEN, false, "http://pay.islevendor.com",
                "https://islevendor.com", "http://backend:4000", null);
        assertThatThrownBy(() -> StartupSafetyChecks.verify(plainHttp, LOCAL_DB))
                .hasMessageContaining("ISLEVENDOR_PAY_PUBLIC_URL");
        var local = new PayProperties(true, "jwt", TOKEN, false, "http://localhost:8081",
                "http://localhost:5173", "http://127.0.0.1:4000", null);
        assertThatCode(() -> StartupSafetyChecks.verify(local, LOCAL_DB)).doesNotThrowAnyException();
    }

    @Test
    void liveNeedsRealCredentialsAndTheTestGatewayMustBeLocal() {
        var liveNoKeys = new PayProperties(false, "jwt", "", false, null, null, null,
                new PayProperties.WiPay("live", true, null, null, null, null, null));
        assertThatThrownBy(() -> StartupSafetyChecks.verify(liveNoKeys, LOCAL_DB))
                .hasMessageContaining("WIPAY_ACCOUNT_NUMBER");
        // The sandbox's published test account is never used for live.
        assertThat(liveNoKeys.wipay().effectiveApiKey()).isEmpty();

        var remoteFake = new PayProperties(false, "jwt", "", false, null, null, null,
                new PayProperties.WiPay("sandbox", false, null, null, null, null, "https://evil.example.com"));
        assertThatThrownBy(() -> StartupSafetyChecks.verify(remoteFake, LOCAL_DB))
                .hasMessageContaining("test-base-url");

        var badOrigin = new PayProperties(false, "jwt", "", false, null, null, null,
                new PayProperties.WiPay("sandbox", false, null, null, null, "Isle Vendor!", null));
        assertThatThrownBy(() -> StartupSafetyChecks.verify(badOrigin, LOCAL_DB))
                .hasMessageContaining("WIPAY_ORIGIN");
    }

    @Test
    void checkoutUrlsMustBeWiPaysOwnHostForTheEnvironment() {
        var sandbox = GatewayEnvironment.SANDBOX;
        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl("https://jmsb.wipayfinancial.com/checkout/abc", sandbox)).isPresent();
        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl("https://JMSB.wipayfinancial.com:443/checkout", sandbox)).isPresent();

        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl("https://jm.wipayfinancial.com/checkout", sandbox)).isEmpty();
        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl("http://jmsb.wipayfinancial.com/checkout", sandbox)).isEmpty();
        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl("https://jmsb.wipayfinancial.com.evil.com/", sandbox)).isEmpty();
        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl("https://evil.com@jmsb.wipayfinancial.com/", sandbox)).isEmpty();
        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl("https://jmsb.wipayfinancial.com:8443/", sandbox)).isEmpty();
        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl("javascript:alert(1)", sandbox)).isEmpty();
        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl("//jmsb.wipayfinancial.com/", sandbox)).isEmpty();
        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl(null, sandbox)).isEmpty();

        assertThat(GatewayUrlPolicy.safeWiPayCheckoutUrl("https://jm.wipayfinancial.com/checkout", GatewayEnvironment.LIVE)).isPresent();
    }
}

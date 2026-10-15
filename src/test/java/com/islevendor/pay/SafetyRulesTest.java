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
        return new PayProperties(enabled, "jwt-secret", serviceToken, remoteOk, new PayProperties.WiPay(env, live));
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
        var noSecret = new PayProperties(false, "", "", false, new PayProperties.WiPay("sandbox", false));
        assertThatThrownBy(() -> StartupSafetyChecks.verify(noSecret, LOCAL_DB)).hasMessageContaining("JWT_SECRET");
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

package com.islevendor.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Pay switched off (the default): logins, roles, the schema, and that nothing can be recorded. */
class SecurityAndSchemaTest extends PayIntegrationTest {

    @Test
    void flywayCreatesThePaymentsSchemaWithWiPayDisabledInSandbox() {
        var row = jdbc.queryForMap(
                "select environment, enabled from payments.gateway_configurations where gateway_code = 'WIPAY'");
        assertThat(row.get("environment")).isEqualTo("SANDBOX");
        assertThat(row.get("enabled")).isEqualTo(false);
        // Nothing was created in the marketplace's schema except the test stand-in.
        assertThat(jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'public' and table_name <> 'users'",
                Integer.class)).isZero();
    }

    @Test
    void healthIsPublicAndRevealsNoDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void anUnlistedPathIsRefused() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
        mvc.perform(get("/anything")).andExpect(status().is4xxClientError());
    }

    @Test
    void acceptsAMarketplaceLogin() throws Exception {
        UUID id = createUser("CUSTOMER");
        mvc.perform(get("/api/pay/status").header("Authorization", "Bearer " + token(id, "CUSTOMER", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.gatewayEnvironment").value("SANDBOX"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
        // Tokens from before session versioning carry no "ver": they mean 0, as in Node.
        mvc.perform(get("/api/pay/status").header("Authorization", "Bearer " + token(id, "CUSTOMER", null)))
                .andExpect(status().isOk());
    }

    @Test
    void refusesMissingForgedUnsignedExpiredAndRevokedLogins() throws Exception {
        UUID id = createUser("CUSTOMER");
        long now = Instant.now().getEpochSecond();
        String claims = "{\"sub\":\"" + id + "\",\"role\":\"CUSTOMER\",\"exp\":" + (now + 3600) + "}";

        mvc.perform(get("/api/pay/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/pay/status").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());

        String wrongSecret = sign("{\"alg\":\"HS256\"}", claims, "another-secret");
        mvc.perform(get("/api/pay/status").header("Authorization", "Bearer " + wrongSecret))
                .andExpect(status().isUnauthorized());

        String algNone = sign("{\"alg\":\"none\"}", claims, JWT_SECRET);
        String unsigned = algNone.substring(0, algNone.lastIndexOf('.') + 1);
        mvc.perform(get("/api/pay/status").header("Authorization", "Bearer " + unsigned))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/pay/status").header("Authorization", "Bearer " + algNone))
                .andExpect(status().isUnauthorized());

        String expired = sign("{\"alg\":\"HS256\"}",
                "{\"sub\":\"" + id + "\",\"role\":\"CUSTOMER\",\"exp\":" + (now - 120) + "}", JWT_SECRET);
        mvc.perform(get("/api/pay/status").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());

        String noExpiry = sign("{\"alg\":\"HS256\"}", "{\"sub\":\"" + id + "\",\"role\":\"CUSTOMER\"}", JWT_SECRET);
        mvc.perform(get("/api/pay/status").header("Authorization", "Bearer " + noExpiry))
                .andExpect(status().isUnauthorized());

        // "Sign out everywhere" in the marketplace bumps token_version.
        String before = token(id, "CUSTOMER", 0);
        jdbc.update("update public.users set token_version = 1 where id = ?", id);
        mvc.perform(get("/api/pay/status").header("Authorization", "Bearer " + before))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/pay/status").header("Authorization", "Bearer " + token(UUID.randomUUID(), "ADMIN", 0)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void gatewaySettingsAreForCurrentAdminsOnlyAndNeverIncludeSecrets() throws Exception {
        UUID customer = createUser("CUSTOMER");
        mvc.perform(get("/api/pay/admin/gateways").header("Authorization", "Bearer " + token(customer, "CUSTOMER", 0)))
                .andExpect(status().isForbidden());

        // The role comes from the database: a token claiming ADMIN for a customer gets nowhere.
        mvc.perform(get("/api/pay/admin/gateways").header("Authorization", "Bearer " + token(customer, "ADMIN", 0)))
                .andExpect(status().isForbidden());

        UUID admin = createUser("ADMIN");
        String body = mvc.perform(get("/api/pay/admin/gateways").header("Authorization", "Bearer " + token(admin, "ADMIN", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("WIPAY"))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[0].apiKeyConfigured").value(true))
                .andExpect(jsonPath("$[0].accountNumberConfigured").value(false))
                .andExpect(jsonPath("$[0].checkoutHost").value("jmsb.wipayfinancial.com"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("configured-but-never-shown").doesNotContain("WIPAY_API_KEY");
    }

    @Test
    void internalEndpointsNeedTheServiceTokenAndAreOffByDefault() throws Exception {
        String key = "checkout-" + UUID.randomUUID();
        String request = """
                {"idempotencyKey":"%s","gateway":"WIPAY","customerUserId":"%s",
                 "orderIds":["%s"],"amount":"1500.00","currency":"JMD"}
                """.formatted(key, UUID.randomUUID(), UUID.randomUUID());

        mvc.perform(post("/internal/v1/payment-intents").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/payment-intents").header("X-IsleVendor-Service-Token", "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());

        // A marketplace admin login is not the service token.
        UUID admin = createUser("ADMIN");
        mvc.perform(post("/internal/v1/payment-intents").header("Authorization", "Bearer " + token(admin, "ADMIN", 0))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/internal/v1/payment-intents").header("X-IsleVendor-Service-Token", SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("IsleVendor Pay is not enabled"));
        assertThat(jdbc.queryForObject("select count(*) from payments.payment_intents where idempotency_key = ?",
                Integer.class, key)).isZero();
    }

    @Test
    void auditEntriesCannotBeChangedOrDeleted() {
        jdbc.update("insert into payments.audit_events (actor_type, action, target_type, target_id) values ('SYSTEM', 'test', 'test', 'x')");
        assertThatThrownBy(() -> jdbc.update("update payments.audit_events set action = 'changed' where action = 'test'"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from payments.audit_events where action = 'test'"))
                .hasMessageContaining("append-only");
    }
}

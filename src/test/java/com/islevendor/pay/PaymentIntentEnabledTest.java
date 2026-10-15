package com.islevendor.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

/** Pay switched on, sandbox only: intents are recorded once per idempotency key, and audited. */
@TestPropertySource(properties = "islevendor.pay.enabled=true")
class PaymentIntentEnabledTest extends PayIntegrationTest {

    private final UUID customer = UUID.randomUUID();
    private final UUID orderA = UUID.randomUUID();
    private final UUID orderB = UUID.randomUUID();

    @BeforeEach
    void enableWiPaySandbox() {
        jdbc.update("update payments.gateway_configurations set enabled = true where gateway_code = 'WIPAY'");
    }

    @AfterEach
    void disableWiPay() {
        jdbc.update("update payments.gateway_configurations set enabled = false where gateway_code = 'WIPAY'");
    }

    private String request(String key, String amount) {
        return """
                {"idempotencyKey":"%s","gateway":"WIPAY","customerUserId":"%s",
                 "orderIds":["%s","%s"],"amount":"%s","currency":"JMD"}
                """.formatted(key, customer, orderA, orderB, amount);
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/internal/v1/payment-intents").header("X-IsleVendor-Service-Token", SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void recordsAnIntentOnceAndAuditsIt() throws Exception {
        String key = "checkout-" + UUID.randomUUID();
        String body = send(request(key, "2550.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amount").value(2550.00))
                .andExpect(jsonPath("$.orderIds.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String id = JsonMapper.builder().build().readTree(body).path("id").asString();

        // A retry with the same key and details returns the same intent.
        send(request(key, "2550.00")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        // The same key for a different amount is refused.
        send(request(key, "1.00")).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select count(*) from payments.payment_intents where idempotency_key = ?",
                Integer.class, key)).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from payments.audit_events where action = 'payment_intent.created' and target_id = ?",
                Integer.class, id)).isOne();

        mvc.perform(get("/internal/v1/payment-intents/" + id).header("X-IsleVendor-Service-Token", SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerUserId").value(customer.toString()));
    }

    @Test
    void refusesADisabledGatewayAndBadInput() throws Exception {
        jdbc.update("update payments.gateway_configurations set enabled = false where gateway_code = 'WIPAY'");
        send(request("checkout-" + UUID.randomUUID(), "100.00")).andExpect(status().isConflict());
        jdbc.update("update payments.gateway_configurations set enabled = true where gateway_code = 'WIPAY'");

        send(request("checkout-" + UUID.randomUUID(), "0.00")).andExpect(status().isBadRequest());
        send(request("checkout-" + UUID.randomUUID(), "10.001")).andExpect(status().isBadRequest());
        send(request("short", "10.00")).andExpect(status().isBadRequest());
        send(request("checkout-" + UUID.randomUUID(), "10.00").replace("JMD", "USD")).andExpect(status().isBadRequest());
        send(request("checkout-" + UUID.randomUUID(), "10.00").replace("\"WIPAY\"", "\"PAYPAL\""))
                .andExpect(status().isConflict());
        send("not json").andExpect(status().isBadRequest());
    }
}

package com.islevendor.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.islevendor.pay.payment.MarketplaceSync;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pay switched on, WiPay sandbox: the whole checkout against a fake WiPay
 * and a fake marketplace (Node) backend.
 */
class PaymentIntentEnabledTest extends PayIntegrationTest {

    static final String WEBHOOK_SECRET = "test-wipay-payments-webhook-secret";
    static final String WEB = "http://localhost:5173";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final HttpServer fakeWiPay = start();
    static final HttpServer fakeMarketplace = start();
    static final List<JsonNode> wipayRequests = new CopyOnWriteArrayList<>();
    static final List<JsonNode> marketplaceCalls = new CopyOnWriteArrayList<>();
    static volatile String wipayMode = "ok";          // ok | refuse | foreign-url
    static volatile String marketplaceAnswer = "echo"; // echo | DUPLICATE | down

    static {
        fakeWiPay.createContext("/plugins/payments/request", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            wipayRequests.add(body);
            String ref = body.path("order_id").asString();
            switch (wipayMode) {
                case "refuse" -> reply(exchange, 422, "{\"message\":\"Invalid account\"}");
                case "foreign-url" -> reply(exchange, 200, "{\"url\":\"https://evil.example.com/pay\",\"transaction_id\":\"X\"}");
                default -> reply(exchange, 200, "{\"url\":\"https://jmsb.wipayfinancial.com/checkout/" + ref
                        + "\",\"transaction_id\":\"SB-" + ref + "\"}");
            }
        });
        fakeMarketplace.createContext("/internal/pay/payments/", exchange -> {
            if (!SERVICE_TOKEN.equals(exchange.getRequestHeaders().getFirst("X-IsleVendor-Service-Token"))) {
                reply(exchange, 401, "{}");
                return;
            }
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            marketplaceCalls.add(body);
            switch (marketplaceAnswer) {
                case "down" -> reply(exchange, 503, "{}");
                case "DUPLICATE" -> reply(exchange, 200, "{\"status\":\"DUPLICATE\"}");
                default -> reply(exchange, 200, "{\"status\":\"" + body.path("status").asString() + "\"}");
            }
        });
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @DynamicPropertySource
    static void pay(DynamicPropertyRegistry registry) {
        registry.add("islevendor.pay.enabled", () -> "true");
        registry.add("islevendor.pay.public-base-url", () -> "http://localhost:8081");
        registry.add("islevendor.pay.marketplace-web-url", () -> WEB);
        registry.add("islevendor.pay.marketplace-internal-url",
                () -> "http://127.0.0.1:" + fakeMarketplace.getAddress().getPort());
        registry.add("islevendor.pay.wipay.test-base-url",
                () -> "http://127.0.0.1:" + fakeWiPay.getAddress().getPort());
        registry.add("islevendor.pay.wipay.payments-webhook-secret", () -> WEBHOOK_SECRET);
        // WiPay's published sandbox account (1234567890 / 123) is used.
        registry.add("islevendor.pay.wipay.account-number", () -> "");
        registry.add("islevendor.pay.wipay.api-key", () -> "");
    }

    @Autowired
    MarketplaceSync marketplaceSync;

    private final UUID customer = UUID.randomUUID();
    private final UUID marketplacePaymentId = UUID.randomUUID();
    private final UUID orderA = UUID.randomUUID();
    private final UUID orderB = UUID.randomUUID();

    @BeforeEach
    void reset() {
        jdbc.update("update payments.gateway_configurations set enabled = true, environment = 'SANDBOX' where gateway_code = 'WIPAY'");
        wipayRequests.clear();
        marketplaceCalls.clear();
        wipayMode = "ok";
        marketplaceAnswer = "echo";
    }

    @AfterEach
    void disableWiPay() {
        jdbc.update("update payments.gateway_configurations set enabled = false where gateway_code = 'WIPAY'");
    }

    private String request(String key, String amount) {
        return """
                {"idempotencyKey":"%s","marketplacePaymentId":"%s","gateway":"WIPAY","customerUserId":"%s",
                 "orderIds":["%s","%s"],"amount":"%s","currency":"JMD","feeStructure":"merchant_absorb",
                 "customer":{"email":"kim@example.com","name":"Kim Brown","phone":"+18765550123"}}
                """.formatted(key, marketplacePaymentId, customer, orderA, orderB, amount);
    }

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/internal/v1/payment-intents").header("X-IsleVendor-Service-Token", SERVICE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Opens a checkout for 2550.00 and returns {id, reference, transactionId}. */
    private String[] openCheckout() throws Exception {
        String body = send(request("checkout-" + UUID.randomUUID(), "2550.00"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode intent = JSON.readTree(body);
        return new String[] {intent.path("id").asString(), intent.path("reference").asString(),
                intent.path("gatewayTransactionId").asString()};
    }

    private String intentStatus(String intentId) {
        return jdbc.queryForObject("select status from payments.payment_intents where id = ?::uuid", String.class, intentId);
    }

    private static String md5(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions customerReturns(String reference, String status, String transactionId, String hash) throws Exception {
        return mvc.perform(get("/gateway/wipay/return")
                .param("status", status).param("order_id", reference)
                .param("transaction_id", transactionId).param("hash", hash));
    }

    private ResultActions webhook(String body, long timestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
        String signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        return mvc.perform(post("/gateway/wipay/webhooks/payments")
                .header("X-WiPay-Webhook-Signature", signature)
                .header("X-WiPay-Webhook-Timestamp", String.valueOf(timestamp))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String successEvent(String eventId, String reference, String transactionId, String total) {
        return """
                {"id":"%s","event":"payment.success","data":{"order_id":"%s","transaction_id":"%s","total":%s}}
                """.formatted(eventId, reference, transactionId, total);
    }

    @Test
    void opensAWiPaySandboxCheckoutOncePerIdempotencyKey() throws Exception {
        String key = "checkout-" + UUID.randomUUID();
        String body = send(request(key, "2550.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.checkoutUrl").value(org.hamcrest.Matchers.startsWith("https://jmsb.wipayfinancial.com/checkout/")))
                .andExpect(jsonPath("$.marketplacePaymentId").value(marketplacePaymentId.toString()))
                .andReturn().getResponse().getContentAsString();
        String id = JSON.readTree(body).path("id").asString();

        assertThat(wipayRequests).hasSize(1);
        JsonNode sent = wipayRequests.get(0);
        assertThat(sent.path("account_number").asString()).isEqualTo("1234567890");
        assertThat(sent.path("environment").asString()).isEqualTo("sandbox");
        assertThat(sent.path("method").asString()).isEqualTo("credit_card_co");
        assertThat(sent.path("currency").asString()).isEqualTo("JMD");
        assertThat(sent.path("total").decimalValue()).isEqualByComparingTo("2550.00");
        assertThat(sent.path("order_id").asString()).matches("[A-Z0-9]{1,16}");
        assertThat(sent.path("response_url").asString()).isEqualTo("http://localhost:8081/gateway/wipay/return");
        assertThat(sent.path("phone").asString()).isEqualTo("+18765550123");
        assertThat(sent.path("fee_structure").asString()).isEqualTo("merchant_absorb");

        // A retry with the same key and details returns the same intent, without asking WiPay again.
        send(request(key, "2550.00")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        // The same key for a different amount is refused.
        send(request(key, "1.00")).andExpect(status().isConflict());
        assertThat(wipayRequests).hasSize(1);

        assertThat(jdbc.queryForObject("select count(*) from payments.payment_intents where idempotency_key = ?",
                Integer.class, key)).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from payments.audit_events where action in ('payment_intent.created', 'payment_intent.checkout_opened') and target_id = ?",
                Integer.class, id)).isEqualTo(2);
    }

    @Test
    void aRefusedOrForeignCheckoutFailsWithoutSendingTheCustomerAnywhere() throws Exception {
        wipayMode = "refuse";
        send(request("checkout-" + UUID.randomUUID(), "100.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.checkoutUrl").doesNotExist())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid account")));

        wipayMode = "foreign-url";
        send(request("checkout-" + UUID.randomUUID(), "100.00").replace(marketplacePaymentId.toString(), UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.checkoutUrl").doesNotExist());
    }

    @Test
    void aVerifiedReturnMarksItPaidAndTellsTheMarketplaceOnce() throws Exception {
        String[] intent = openCheckout();
        String hash = md5(intent[2] + "2550.00" + "123");

        customerReturns(intent[1], "success", intent[2], hash)
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", WEB + "/payment/" + marketplacePaymentId));
        assertThat(intentStatus(intent[0])).isEqualTo("PAID");
        assertThat(marketplaceCalls).hasSize(1);
        assertThat(marketplaceCalls.get(0).path("status").asString()).isEqualTo("PAID");
        assertThat(marketplaceCalls.get(0).path("amount").asString()).isEqualTo("2550.00");
        assertThat(marketplaceCalls.get(0).path("transactionId").asString()).isEqualTo(intent[2]);

        // The customer reloads the return page: nothing changes, nobody is told twice.
        customerReturns(intent[1], "success", intent[2], hash).andExpect(status().isSeeOther());
        marketplaceSync.syncOutstanding();
        assertThat(marketplaceCalls).hasSize(1);
    }

    @Test
    void aForgedOrAlteredReturnChangesNothing() throws Exception {
        String[] intent = openCheckout();

        customerReturns(intent[1], "success", intent[2], "0".repeat(32)).andExpect(status().isSeeOther());
        // A real hash, but for a smaller total.
        customerReturns(intent[1], "success", intent[2], md5(intent[2] + "1.00" + "123")).andExpect(status().isSeeOther());
        // A real hash for another transaction id.
        customerReturns(intent[1], "success", "OTHER-TX", md5("OTHER-TX" + "2550.00" + "123")).andExpect(status().isSeeOther());

        assertThat(intentStatus(intent[0])).isEqualTo("PENDING");
        assertThat(marketplaceCalls).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from payments.audit_events where action = 'payment_intent.return_rejected' and target_id = ?",
                Integer.class, intent[0])).isEqualTo(3);

        // An unknown reference just goes to the account page.
        customerReturns("NOSUCHREF", "success", "X", "0".repeat(32))
                .andExpect(header().string("Location", WEB + "/account"));
    }

    @Test
    void aDeclinedCardFailsAndALaterVerifiedSuccessStillWins() throws Exception {
        String[] intent = openCheckout();
        customerReturns(intent[1], "failed", intent[2], "").andExpect(status().isSeeOther());
        assertThat(intentStatus(intent[0])).isEqualTo("FAILED");
        assertThat(marketplaceCalls).hasSize(1);
        assertThat(marketplaceCalls.get(0).path("status").asString()).isEqualTo("FAILED");

        long now = Instant.now().getEpochSecond();
        webhook(successEvent("evt-" + UUID.randomUUID(), intent[1], intent[2], "2550.00"), now, WEBHOOK_SECRET)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPLIED"));
        assertThat(intentStatus(intent[0])).isEqualTo("PAID");
        assertThat(marketplaceCalls).hasSize(2);
        assertThat(marketplaceCalls.get(1).path("status").asString()).isEqualTo("PAID");

        // A late failure return can't undo a payment.
        customerReturns(intent[1], "failed", intent[2], "").andExpect(status().isSeeOther());
        assertThat(intentStatus(intent[0])).isEqualTo("PAID");
    }

    @Test
    void webhooksMustBeSignedRecentAndForTheRightAmountAndAreHandledOnce() throws Exception {
        String[] intent = openCheckout();
        long now = Instant.now().getEpochSecond();
        String eventId = "evt-" + UUID.randomUUID();
        String event = successEvent(eventId, intent[1], intent[2], "2550.00");

        webhook(event, now, "wrong-secret").andExpect(status().isBadRequest());
        webhook(event, now - 600, WEBHOOK_SECRET).andExpect(status().isBadRequest());
        mvc.perform(post("/gateway/wipay/webhooks/payments").contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isBadRequest());

        webhook(successEvent("evt-" + UUID.randomUUID(), intent[1], intent[2], "1.00"), now, WEBHOOK_SECRET)
                .andExpect(jsonPath("$.outcome").value("AMOUNT_MISMATCH"));
        webhook(successEvent("evt-" + UUID.randomUUID(), intent[1], "OTHER-TX", "2550.00"), now, WEBHOOK_SECRET)
                .andExpect(jsonPath("$.outcome").value("UNMATCHED"));
        assertThat(intentStatus(intent[0])).isEqualTo("PENDING");

        webhook(event, now, WEBHOOK_SECRET).andExpect(jsonPath("$.outcome").value("APPLIED"));
        webhook(event, now, WEBHOOK_SECRET).andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        assertThat(intentStatus(intent[0])).isEqualTo("PAID");
        assertThat(marketplaceCalls).hasSize(1);
        assertThat(jdbc.queryForObject("select outcome from payments.webhook_events where event_id = ?", String.class, eventId))
                .isEqualTo("APPLIED");
    }

    @Test
    void ordersAlreadyPaidElsewhereMakeItADuplicateForRefund() throws Exception {
        marketplaceAnswer = "DUPLICATE";
        String[] intent = openCheckout();
        customerReturns(intent[1], "success", intent[2], md5(intent[2] + "2550.00" + "123"));
        assertThat(intentStatus(intent[0])).isEqualTo("DUPLICATE");
        marketplaceSync.syncOutstanding();
        assertThat(marketplaceCalls).hasSize(1);
    }

    @Test
    void keepsTellingTheMarketplaceUntilItAcknowledges() throws Exception {
        marketplaceAnswer = "down";
        String[] intent = openCheckout();
        customerReturns(intent[1], "success", intent[2], md5(intent[2] + "2550.00" + "123"));
        assertThat(intentStatus(intent[0])).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select marketplace_synced_status from payments.payment_intents where id = ?::uuid",
                String.class, intent[0])).isNull();

        marketplaceAnswer = "echo";
        marketplaceSync.syncOutstanding();
        assertThat(jdbc.queryForObject("select marketplace_synced_status from payments.payment_intents where id = ?::uuid",
                String.class, intent[0])).isEqualTo("PAID");
        marketplaceSync.syncOutstanding();
        assertThat(marketplaceCalls.stream().filter(c -> c.path("paymentIntentId").asString().equals(intent[0])))
                .hasSize(2);
    }

    @Test
    void refusesADisabledOrMismatchedGatewayAndBadInput() throws Exception {
        jdbc.update("update payments.gateway_configurations set enabled = false where gateway_code = 'WIPAY'");
        send(request("checkout-" + UUID.randomUUID(), "100.00")).andExpect(status().isConflict());

        // The dashboard can't switch to live on a sandbox deployment.
        jdbc.update("update payments.gateway_configurations set enabled = true, environment = 'LIVE' where gateway_code = 'WIPAY'");
        send(request("checkout-" + UUID.randomUUID(), "100.00")).andExpect(status().isConflict());
        jdbc.update("update payments.gateway_configurations set environment = 'SANDBOX' where gateway_code = 'WIPAY'");
        assertThat(wipayRequests).isEmpty();

        send(request("checkout-" + UUID.randomUUID(), "0.00")).andExpect(status().isBadRequest());
        send(request("checkout-" + UUID.randomUUID(), "10.001")).andExpect(status().isBadRequest());
        send(request("short", "10.00")).andExpect(status().isBadRequest());
        send(request("checkout-" + UUID.randomUUID(), "10.00").replace("JMD", "USD")).andExpect(status().isBadRequest());
        send(request("checkout-" + UUID.randomUUID(), "10.00").replace("merchant_absorb", "free")).andExpect(status().isBadRequest());
        send(request("checkout-" + UUID.randomUUID(), "10.00").replace("kim@example.com", "not-an-email")).andExpect(status().isBadRequest());
        send(request("checkout-" + UUID.randomUUID(), "10.00").replace("\"WIPAY\"", "\"PAYPAL\""))
                .andExpect(status().isConflict());
        send("not json").andExpect(status().isBadRequest());
    }
}

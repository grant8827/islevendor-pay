package com.islevendor.pay.gateway;

import com.islevendor.pay.config.PayProperties;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * WiPay's Payments API (hosted checkout, https://docs.wipayfinancial.com/payments-api).
 * The request host is fixed by {@link GatewayUrlPolicy} for the environment;
 * only tests may point it at a local fake, and start-up refuses anything else.
 */
@Component
public class WiPayCheckoutClient {

    public record CheckoutRequest(String reference, UUID paymentIntentId, BigDecimal total, String feeStructure,
            String email, String name, String phone) {
    }

    public record CheckoutPage(String url, String transactionId) {
    }

    /** WiPay refused or couldn't be reached. Nothing was charged: the customer never saw a card form. */
    public static class CheckoutUnavailableException extends RuntimeException {
        public CheckoutUnavailableException(String message) {
            super(message);
        }
    }

    private final PayProperties props;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public WiPayCheckoutClient(PayProperties props) {
        this.props = props;
    }

    public GatewayEnvironment environment() {
        return props.wipay().isLive() ? GatewayEnvironment.LIVE : GatewayEnvironment.SANDBOX;
    }

    private String requestUrl() {
        String base = props.wipay().testBaseUrl().isEmpty()
                ? "https://" + GatewayUrlPolicy.wipayHost(environment())
                : props.wipay().testBaseUrl();
        return base + "/plugins/payments/request";
    }

    /** Where WiPay sends the customer back: built from this service's own address. */
    public String responseUrl() {
        return props.publicBaseUrl() + "/gateway/wipay/return";
    }

    public CheckoutPage openCheckout(CheckoutRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("account_number", props.wipay().effectiveAccountNumber());
        body.put("country_code", "JM");
        body.put("currency", "JMD");
        body.put("environment", environment() == GatewayEnvironment.LIVE ? "live" : "sandbox");
        body.put("fee_structure", req.feeStructure());
        body.put("method", "credit_card_co");
        body.put("order_id", req.reference());
        body.put("origin", props.wipay().origin());
        body.put("response_url", responseUrl());
        body.put("total", req.total().setScale(2));
        body.put("email", req.email());
        body.put("name", req.name());
        if (req.phone() != null) {
            body.put("phone", req.phone());
        }
        body.put("data", json.writeValueAsString(Map.of("paymentIntentId", req.paymentIntentId().toString())));
        body.put("version", "1.0.0");

        HttpResponse<String> res;
        try {
            res = http.send(HttpRequest.newBuilder(URI.create(requestUrl()))
                            .timeout(Duration.ofSeconds(20))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new CheckoutUnavailableException("WiPay could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CheckoutUnavailableException("Interrupted while contacting WiPay");
        }

        JsonNode answer;
        try {
            answer = json.readTree(res.body());
        } catch (tools.jackson.core.JacksonException e) {
            answer = json.createObjectNode();
        }
        String url = answer.path("url").asString("");
        if (res.statusCode() / 100 != 2 || url.isEmpty()) {
            String message = answer.path("message").asString("");
            throw new CheckoutUnavailableException(
                    message.isEmpty() ? "WiPay returned " + res.statusCode() : truncate(message, 200));
        }
        // Never send a customer anywhere but WiPay's own checkout.
        if (GatewayUrlPolicy.safeWiPayCheckoutUrl(url, environment()).isEmpty()) {
            throw new CheckoutUnavailableException("WiPay returned an unexpected checkout address");
        }
        String transactionId = answer.path("transaction_id").asString("");
        return new CheckoutPage(url, transactionId.isEmpty() ? null : transactionId);
    }

    /**
     * WiPay's proof on the customer's return: md5(transaction_id + total + API key),
     * where total is the amount <em>we</em> asked for, so a return for another
     * amount, transaction or payment fails.
     */
    public boolean returnHashMatches(String transactionId, BigDecimal total, String receivedHash) {
        String apiKey = props.wipay().effectiveApiKey();
        if (apiKey.isEmpty() || receivedHash == null || !receivedHash.matches("[0-9a-fA-F]{32}")) {
            return false;
        }
        try {
            byte[] expected = MessageDigest.getInstance("MD5").digest(
                    (transactionId + total.setScale(2).toPlainString() + apiKey).getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(
                    HexFormat.of().formatHex(expected).getBytes(StandardCharsets.US_ASCII),
                    receivedHash.toLowerCase().getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}

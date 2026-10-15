package com.islevendor.pay;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Runs against a real local PostgreSQL (no Docker needed):
 * {@code createdb islevendor_pay_test}, or set PAY_TEST_DATABASE_URL.
 * public.users stands in for the marketplace's table, with the three
 * columns the payments role is allowed to read.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=${PAY_TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/islevendor_pay_test}",
        "islevendor.pay.marketplace-jwt-secret=" + PayIntegrationTest.JWT_SECRET,
        "islevendor.pay.service-token=" + PayIntegrationTest.SERVICE_TOKEN,
        "WIPAY_API_KEY=configured-but-never-shown",
})
@AutoConfigureMockMvc
public abstract class PayIntegrationTest {

    static final String JWT_SECRET = "test-marketplace-jwt-secret";
    static final String SERVICE_TOKEN = "test-service-token-0123456789abcdef-0123";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void marketplaceUsersTable() {
        jdbc.execute("""
                create table if not exists public.users (
                    id uuid primary key,
                    role text not null,
                    token_version int not null default 0
                )""");
    }

    protected UUID createUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into public.users (id, role, token_version) values (?, ?, 0)", id, role);
        return id;
    }

    /** A token as the Node backend's signAuthToken issues it. */
    protected static String token(UUID userId, String role, Integer ver) {
        long now = Instant.now().getEpochSecond();
        String verClaim = ver == null ? "" : ",\"ver\":" + ver;
        return sign("{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                "{\"sub\":\"" + userId + "\",\"role\":\"" + role + "\"" + verClaim
                        + ",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}",
                JWT_SECRET);
    }

    protected static String sign(String header, String payload, String secret) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String unsigned = b64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return unsigned + "." + b64.encodeToString(mac.doFinal(unsigned.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

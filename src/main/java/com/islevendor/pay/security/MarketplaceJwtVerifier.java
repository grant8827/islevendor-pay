package com.islevendor.pay.security;

import com.islevendor.pay.config.PayProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies the marketplace's own login tokens, exactly as the Node backend's
 * verifyAuthToken does, so customers, vendors and the Superadmin use one
 * account everywhere:
 *
 * <ol>
 *   <li>HS256 signature with the shared JWT_SECRET (any other algorithm, including "none", is refused);</li>
 *   <li>not expired ({@code exp} is required);</li>
 *   <li>the user still exists and the token's {@code ver} matches users.token_version, so
 *       "sign out everywhere" in the marketplace takes effect here immediately.</li>
 * </ol>
 *
 * The role comes from the database, not the token, so a demoted admin loses
 * access at once (the Node side's requireCurrentAdmin).
 */
public class MarketplaceJwtVerifier {

    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();
    private static final long CLOCK_SKEW_SECONDS = 30;

    private final byte[] secret;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper json = JsonMapper.builder().build();

    public MarketplaceJwtVerifier(PayProperties props, JdbcTemplate jdbc, Clock clock) {
        this.secret = props.marketplaceJwtSecret().getBytes(StandardCharsets.UTF_8);
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Returns the user, or throws {@link InvalidTokenException}. Database errors propagate unchanged. */
    public MarketplaceUser verify(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new InvalidTokenException("malformed");
        }

        JsonNode header = decodeJson(parts[0]);
        if (!"HS256".equals(header.path("alg").asString(""))) {
            throw new InvalidTokenException("unsupported algorithm");
        }

        byte[] expected = hmac((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!MessageDigest.isEqual(expected, decode(parts[2]))) {
            throw new InvalidTokenException("bad signature");
        }

        JsonNode claims = decodeJson(parts[1]);
        long now = clock.instant().getEpochSecond();
        JsonNode exp = claims.path("exp");
        if (!exp.isNumber() || exp.asLong() + CLOCK_SKEW_SECONDS < now) {
            throw new InvalidTokenException("expired");
        }
        JsonNode nbf = claims.path("nbf");
        if (nbf.isNumber() && nbf.asLong() - CLOCK_SKEW_SECONDS > now) {
            throw new InvalidTokenException("not yet valid");
        }

        UUID userId;
        try {
            userId = UUID.fromString(claims.path("sub").asString(""));
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("malformed subject");
        }
        JsonNode ver = claims.path("ver");
        int tokenVersion = ver.isMissingNode() || ver.isNull() ? 0 : ver.asInt(-1);

        List<MarketplaceUser> rows = jdbc.query(
                "select role::text as role, token_version from public.users where id = ?",
                (rs, i) -> rs.getInt("token_version") == tokenVersion
                        ? new MarketplaceUser(userId, rs.getString("role"))
                        : null,
                userId);
        if (rows.isEmpty() || rows.get(0) == null) {
            throw new InvalidTokenException("revoked");
        }
        return rows.get(0);
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode decodeJson(String part) {
        try {
            JsonNode node = json.readTree(decode(part));
            if (!node.isObject()) {
                throw new InvalidTokenException("malformed");
            }
            return node;
        } catch (tools.jackson.core.JacksonException e) {
            throw new InvalidTokenException("malformed");
        }
    }

    private static byte[] decode(String part) {
        try {
            return B64URL.decode(part);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("malformed");
        }
    }

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String reason) {
            super(reason);
        }
    }
}

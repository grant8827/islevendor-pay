package com.islevendor.pay.security;

import com.islevendor.pay.config.PayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** The two ways in: a marketplace login on /api/pay/**, and the Node backend's service token on /internal/**. */
final class AuthenticationFilters {

    static final String SERVICE_TOKEN_HEADER = "X-IsleVendor-Service-Token";

    private AuthenticationFilters() {
    }

    /** Bearer marketplace login. A present-but-invalid token is a 401 straight away. */
    static final class MarketplaceTokenFilter extends OncePerRequestFilter {

        private final MarketplaceJwtVerifier verifier;

        MarketplaceTokenFilter(MarketplaceJwtVerifier verifier) {
            this.verifier = verifier;
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/api/pay/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                MarketplaceUser user;
                try {
                    user = verifier.verify(header.substring("Bearer ".length()).trim());
                } catch (MarketplaceJwtVerifier.InvalidTokenException e) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                var auth = new UsernamePasswordAuthenticationToken(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }
    }

    /** Shared secret between the Node backend and this service, compared in constant time. */
    static final class ServiceTokenFilter extends OncePerRequestFilter {

        private final byte[] expectedDigest;

        ServiceTokenFilter(PayProperties props) {
            // An unset token can never match: blank tokens are refused below.
            this.expectedDigest = props.serviceToken().isBlank() ? null : sha256(props.serviceToken());
        }

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/internal/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String presented = request.getHeader(SERVICE_TOKEN_HEADER);
            if (expectedDigest != null && presented != null && !presented.isBlank()
                    && MessageDigest.isEqual(expectedDigest, sha256(presented))) {
                var auth = new UsernamePasswordAuthenticationToken(
                        "islevendor-marketplace", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICE")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }

        private static byte[] sha256(String value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

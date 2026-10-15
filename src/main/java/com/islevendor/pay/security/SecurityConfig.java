package com.islevendor.pay.security;

import com.islevendor.pay.config.PayProperties;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

/**
 * Stateless: no sessions, no cookies, no login form. Anything not listed is
 * refused, so a new endpoint is closed until it is deliberately opened here.
 */
@Configuration
public class SecurityConfig {

    @Bean
    MarketplaceJwtVerifier marketplaceJwtVerifier(PayProperties props, JdbcTemplate jdbc, Clock clock) {
        return new MarketplaceJwtVerifier(props, jdbc, clock);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, MarketplaceJwtVerifier verifier, PayProperties props)
            throws Exception {
        // Created here rather than as beans, so the servlet container doesn't also register them.
        var marketplaceFilter = new AuthenticationFilters.MarketplaceTokenFilter(verifier);
        var serviceFilter = new AuthenticationFilters.ServiceTokenFilter(props);

        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(c -> c.disable())
                .addFilterBefore(serviceFilter, AnonymousAuthenticationFilter.class)
                .addFilterBefore(marketplaceFilter, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/internal/**").hasRole("SERVICE")
                        .requestMatchers("/api/pay/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/pay/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }

    /** No local accounts: stops Spring Boot generating a default user and password. */
    @Bean
    UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}

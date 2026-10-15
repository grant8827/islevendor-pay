package com.islevendor.pay.gateway;

import java.time.Instant;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only gateway view for the Superadmin. Says whether each secret is
 * configured, never what it is. (The Superadmin's screens stay in the
 * existing React admin console; this is the API they will call.)
 */
@RestController
@RequestMapping("/api/pay/admin/gateways")
public class AdminGatewayController {

    private final GatewayConfigurationRepository gateways;
    private final Environment environment;

    public AdminGatewayController(GatewayConfigurationRepository gateways, Environment environment) {
        this.gateways = gateways;
        this.environment = environment;
    }

    public record GatewayView(
            String code,
            String displayName,
            GatewayEnvironment environment,
            boolean enabled,
            boolean accountNumberConfigured,
            boolean apiKeyConfigured,
            String checkoutHost,
            Instant updatedAt) {
    }

    @GetMapping
    public List<GatewayView> list() {
        return gateways.findAll().stream()
                .map(g -> new GatewayView(
                        g.getCode(),
                        g.getDisplayName(),
                        g.getEnvironment(),
                        g.isEnabled(),
                        isSet(g.getAccountNumberRef()),
                        isSet(g.getApiKeyRef()),
                        "WIPAY".equals(g.getCode()) ? GatewayUrlPolicy.wipayHost(g.getEnvironment()) : null,
                        g.getUpdatedAt()))
                .toList();
    }

    // The reference names are constrained by the database to SECRET_NAME
    // form and are not editable through any endpoint.
    private boolean isSet(String ref) {
        if (ref == null) {
            return false;
        }
        String value = environment.getProperty(ref);
        return value != null && !value.isBlank();
    }
}

package com.islevendor.pay.gateway;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A payment gateway's non-secret settings. Secrets are held by name only (the *Ref fields). */
@Entity
@Table(name = "gateway_configurations")
public class GatewayConfiguration {

    @Id
    @Column(name = "gateway_code")
    private String code;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GatewayEnvironment environment;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "account_number_ref")
    private String accountNumberRef;

    @Column(name = "api_key_ref")
    private String apiKeyRef;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected GatewayConfiguration() {
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public GatewayEnvironment getEnvironment() {
        return environment;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getAccountNumberRef() {
        return accountNumberRef;
    }

    public String getApiKeyRef() {
        return apiKeyRef;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

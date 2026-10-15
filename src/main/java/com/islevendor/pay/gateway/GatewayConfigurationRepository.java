package com.islevendor.pay.gateway;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GatewayConfigurationRepository extends JpaRepository<GatewayConfiguration, String> {
}

package com.islevendor.pay.web;

import com.islevendor.pay.config.PayProperties;
import com.islevendor.pay.security.MarketplaceUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets the dashboards ask whether IsleVendor Pay is switched on. Any signed-in user. */
@RestController
public class PayStatusController {

    public record PayStatus(boolean enabled, String gatewayEnvironment, boolean livePaymentsAuthorized, String role) {
    }

    private final PayProperties props;

    public PayStatusController(PayProperties props) {
        this.props = props;
    }

    @GetMapping("/api/pay/status")
    public PayStatus status(@AuthenticationPrincipal MarketplaceUser user) {
        return new PayStatus(
                props.enabled(),
                props.wipay().isLive() ? "LIVE" : "SANDBOX",
                props.wipay().liveAuthorized(),
                user.role());
    }
}

package com.islevendor.pay.security;

import java.util.UUID;

/** A signed-in marketplace user, with the role read from the database (not trusted from the token). */
public record MarketplaceUser(UUID id, String role) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}

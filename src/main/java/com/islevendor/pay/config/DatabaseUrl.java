package com.islevendor.pay.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Accepts the database address in the forms hosts hand out: JDBC
 * ({@code jdbc:postgresql://host:5432/db}) or URL
 * ({@code postgresql://user:password@host:5432/db?schema=public}, as Railway
 * and Prisma use). Stray quotes and spaces from copy-pasting are removed.
 */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

    public static DatabaseUrl parse(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                || value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (!value.startsWith("postgres://") && !value.startsWith("postgresql://")) {
            return new DatabaseUrl(value, null, null);
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            return new DatabaseUrl(value, null, null);
        }
        if (uri.getHost() == null) {
            return new DatabaseUrl(value, null, null);
        }
        // The query is dropped: Prisma's ?schema=public means nothing to JDBC,
        // and the payments schema is set by Flyway and Hibernate.
        String jdbc = "jdbc:postgresql://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                + (uri.getRawPath() == null ? "" : uri.getRawPath());
        String user = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) {
            int colon = userInfo.indexOf(':');
            user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
        }
        return new DatabaseUrl(jdbc, user, password);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}

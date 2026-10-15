package com.islevendor.pay.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Before migrating: refuse to run as a PostgreSQL superuser. The payments
 * service must use its own limited role (db/setup/001_payments_role.sql);
 * with a superuser's login, a bug or a breach here could change any
 * marketplace table. (A database on this machine is exempt: local
 * PostgreSQL installs usually log developers in as superusers.)
 */
@Configuration
public class DatabaseRoleCheck {

    @Bean
    FlywayMigrationStrategy refuseSuperuserThenMigrate(@Value("${spring.datasource.url}") String url) {
        boolean local = StartupSafetyChecks.isLocalDatabase(url);
        return flyway -> {
            if (local) {
                flyway.migrate();
                return;
            }
            try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "select current_user, rolsuper from pg_roles where rolname = current_user")) {
                if (rs.next() && rs.getBoolean("rolsuper")) {
                    throw new IllegalStateException("IsleVendor Pay refused to start: database user \""
                            + rs.getString(1) + "\" is a superuser. Use the islevendor_pay role "
                            + "(ISLEVENDOR_PAY_DATABASE_USERNAME / ISLEVENDOR_PAY_DATABASE_PASSWORD).");
                }
            } catch (SQLException e) {
                throw new IllegalStateException("IsleVendor Pay could not check its database role", e);
            }
            flyway.migrate();
        };
    }
}

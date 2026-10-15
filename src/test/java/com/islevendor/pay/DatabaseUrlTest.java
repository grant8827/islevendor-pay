package com.islevendor.pay;

import static org.assertj.core.api.Assertions.assertThat;

import com.islevendor.pay.config.DatabaseUrl;
import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @Test
    void keepsJdbcUrlsAsTheyAre() {
        var url = DatabaseUrl.parse("jdbc:postgresql://localhost:5432/islevendor_pay_dev");
        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/islevendor_pay_dev");
        assertThat(url.username()).isNull();
    }

    @Test
    void convertsRailwayAndPrismaStyleUrls() {
        var url = DatabaseUrl.parse("postgresql://islevendor_pay:s3cr%40t@postgres.railway.internal:5432/islevendor?schema=public");
        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://postgres.railway.internal:5432/islevendor");
        assertThat(url.username()).isEqualTo("islevendor_pay");
        assertThat(url.password()).isEqualTo("s3cr@t");

        assertThat(DatabaseUrl.parse("postgres://u@db.example.com/pay").jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com/pay");
    }

    @Test
    void stripsCopyPasteQuotesAndSpaces() {
        assertThat(DatabaseUrl.parse("  \"jdbc:postgresql://h:5432/d\" ").jdbcUrl()).isEqualTo("jdbc:postgresql://h:5432/d");
        assertThat(DatabaseUrl.parse(" 'postgresql://h:5432/d' ").jdbcUrl()).isEqualTo("jdbc:postgresql://h:5432/d");
    }
}

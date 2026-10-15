package com.islevendor.pay.config;

import java.time.Clock;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PayProperties.class)
public class PayConfiguration {

    /** Runs before any bean is created, so a bad configuration stops start-up before Flyway touches a database. */
    @Bean
    static BeanFactoryPostProcessor startupSafetyChecks() {
        return new SafetyCheckRunner();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    static final class SafetyCheckRunner implements BeanFactoryPostProcessor, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            PayProperties props = Binder.get(environment)
                    .bind("islevendor.pay", PayProperties.class)
                    .orElseGet(() -> new PayProperties(false, "", "", false, null, null, null, null));
            StartupSafetyChecks.verify(props, environment.getProperty("spring.datasource.url"));
        }
    }
}

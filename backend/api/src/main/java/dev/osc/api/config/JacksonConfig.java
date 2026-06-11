package dev.osc.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a classic Jackson 2 {@link ObjectMapper} bean.
 *
 * <p>Spring Boot 4 / Spring Framework 7 moved their HTTP message conversion to Jackson 3
 * (the {@code tools.jackson} packages) and no longer auto-configure a
 * {@code com.fasterxml.jackson.databind.ObjectMapper} (Jackson 2) bean. Several production
 * components still depend on the Jackson 2 mapper for JSONB (de)serialization — notably
 * {@code R2dbcRecordRepository} — so without this bean the application context fails to start
 * with {@code NoSuchBeanDefinitionException: ObjectMapper}.</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}

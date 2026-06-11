package dev.osc.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a Jackson 2 {@link ObjectMapper} bean for the JSONB (de)serialization done by
 * the R2DBC repositories (record data, automation definitions, audit payloads).
 *
 * <p>Spring Boot 4 auto-configures Jackson 3 ({@code tools.jackson}) only, so the
 * {@code com.fasterxml} ObjectMapper these repositories inject is no longer available
 * as a bean unless we register it ourselves.</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}

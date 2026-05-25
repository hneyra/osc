package dev.osc.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link AwsSecretsManagerConfig} registers a {@link SecretsManagerClient}
 * bean and that {@link DatabaseSecretsRefresher} is wired and loaded correctly when
 * {@code osc.aws.secrets-manager.enabled=true}.
 *
 * <p>Uses {@code @SpringJUnitConfig} with an explicit configuration class list so that
 * only the beans under test are loaded — no full Spring Boot auto-configuration, no
 * database connection, no real AWS credentials required.</p>
 */
@SpringJUnitConfig(classes = {
        AwsSecretsManagerConfig.class,
        DatabaseSecretsRefresher.class,
        AwsSecretsManagerConfigTest.TestInfrastructureConfig.class,
})
@TestPropertySource(properties = {
        "osc.aws.secrets-manager.enabled=true",
        "osc.aws.secrets-manager.db-secret-name=test-secret",
})
@EnableScheduling
class AwsSecretsManagerConfigTest {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * The {@link SecretsManagerClient} bean must be present when the property is enabled.
     */
    @Test
    @DisplayName("SecretsManagerClient bean is registered when secrets-manager is enabled")
    void secretsManagerClientBeanIsPresent() {
        assertThat(applicationContext.containsBean("secretsManagerClient"))
                .as("SecretsManagerClient bean should be registered when " +
                        "osc.aws.secrets-manager.enabled=true")
                .isTrue();

        SecretsManagerClient bean = applicationContext.getBean(SecretsManagerClient.class);
        assertThat(bean).isNotNull();
    }

    /**
     * {@link DatabaseSecretsRefresher} must also be present and have called
     * Secrets Manager on startup.
     */
    @Test
    @DisplayName("DatabaseSecretsRefresher bean is registered when secrets-manager is enabled")
    void databaseSecretsRefresherBeanIsPresent() {
        assertThat(applicationContext.containsBean("databaseSecretsRefresher"))
                .as("DatabaseSecretsRefresher bean should be registered when " +
                        "osc.aws.secrets-manager.enabled=true")
                .isTrue();
    }

    /**
     * After startup, DatabaseSecretsRefresher must have injected R2DBC credentials
     * into the Spring Environment via the named property source.
     */
    @Test
    @DisplayName("DatabaseSecretsRefresher populates R2DBC properties after startup")
    void credentialsArePopulatedInEnvironment() {
        ConfigurableEnvironment env = applicationContext.getBean(ConfigurableEnvironment.class);

        assertThat(env.getPropertySources().contains(DatabaseSecretsRefresher.PROPERTY_SOURCE_NAME))
                .as("Property source '" + DatabaseSecretsRefresher.PROPERTY_SOURCE_NAME
                        + "' should be present after startup")
                .isTrue();

        assertThat(env.getProperty("spring.r2dbc.username"))
                .isEqualTo("osc_admin");
        assertThat(env.getProperty("spring.r2dbc.password"))
                .isEqualTo("test-password");
    }

    // -----------------------------------------------------------------------
    // Test infrastructure
    // -----------------------------------------------------------------------

    /**
     * Provides a mock {@link SecretsManagerClient} and an {@link ObjectMapper} so that
     * {@link DatabaseSecretsRefresher#loadOnStartup()} succeeds without real AWS credentials,
     * and a {@link ConfigurableEnvironment} for property verification.
     */
    @Configuration
    static class TestInfrastructureConfig {

        @Bean
        @Primary
        SecretsManagerClient mockSecretsManagerClient() {
            GetSecretValueResponse response = GetSecretValueResponse.builder()
                    .secretString("""
                            {
                                "host": "localhost",
                                "port": "5432",
                                "dbname": "osc",
                                "username": "osc_admin",
                                "password": "test-password"
                            }
                            """)
                    .build();

            SecretsManagerClient mockClient = mock(SecretsManagerClient.class);
            when(mockClient.getSecretValue(any(GetSecretValueRequest.class))).thenReturn(response);
            return mockClient;
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}

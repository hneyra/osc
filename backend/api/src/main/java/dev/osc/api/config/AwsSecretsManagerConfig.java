package dev.osc.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Registers an AWS Secrets Manager client bean.
 *
 * <p>Only active when {@code osc.aws.secrets-manager.enabled=true} is set
 * (e.g. via the {@code aws} Spring profile or an explicit property).
 * In local development the bean is absent, so no AWS credentials are required.</p>
 *
 * <p>The client uses the default AWS SDK credential chain
 * (env vars → instance profile → ECS task role → etc.).</p>
 */
@Configuration
@ConditionalOnProperty(name = "osc.aws.secrets-manager.enabled", havingValue = "true")
public class AwsSecretsManagerConfig {

    private static final Logger log = LoggerFactory.getLogger(AwsSecretsManagerConfig.class);

    /**
     * Creates and returns a {@link SecretsManagerClient} using the default
     * AWS credential chain and region configured via the SDK (env var
     * {@code AWS_DEFAULT_REGION} or the property {@code osc.aws.region}).
     *
     * @return a fully initialized SecretsManagerClient
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        log.info("Initialising AWS Secrets Manager client");
        return SecretsManagerClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }
}

package dev.osc.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Periodically fetches database credentials from AWS Secrets Manager and
 * refreshes the Spring R2DBC / Flyway connection properties.
 *
 * <p>The secret must be a JSON object with the following keys:
 * {@code host}, {@code port}, {@code dbname}, {@code username}, {@code password}.</p>
 *
 * <p>Refreshes every 24 hours.  An initial load also runs at startup via
 * {@link jakarta.annotation.PostConstruct}.  The property source named
 * {@code awsSecretsManagerDb} is inserted at the highest priority so it
 * overrides anything in {@code application.yml} or environment variables.</p>
 *
 * <p>Only active when {@code osc.aws.secrets-manager.enabled=true}.</p>
 */
@Component
@ConditionalOnProperty(name = "osc.aws.secrets-manager.enabled", havingValue = "true")
public class DatabaseSecretsRefresher {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSecretsRefresher.class);

    /**
     * Name of the property source injected into the Spring Environment.
     * Using a stable name allows idempotent replacement on refresh.
     */
    static final String PROPERTY_SOURCE_NAME = "awsSecretsManagerDb";

    private final SecretsManagerClient secretsManagerClient;
    private final ConfigurableEnvironment environment;
    private final ObjectMapper objectMapper;
    private final String secretName;

    public DatabaseSecretsRefresher(
            SecretsManagerClient secretsManagerClient,
            ConfigurableEnvironment environment,
            ObjectMapper objectMapper) {
        this.secretsManagerClient = secretsManagerClient;
        this.environment = environment;
        this.objectMapper = objectMapper;
        this.secretName = environment.getProperty(
                "osc.aws.secrets-manager.db-secret-name", "osc-db-secret");
    }

    /**
     * Loads credentials at startup so the datasource is ready before any
     * request arrives.  Failures here abort the application context refresh —
     * this is intentional: a misconfigured secret is a hard error in production.
     */
    @jakarta.annotation.PostConstruct
    public void loadOnStartup() {
        log.info("Loading DB credentials from Secrets Manager at startup (secret={})", secretName);
        refreshCredentials();
    }

    /**
     * Refreshes every 24 hours.  Secrets Manager secret rotation typically
     * happens on a similar cadence, so this keeps credentials in sync.
     */
    @Scheduled(fixedRateString = "${osc.aws.secrets-manager.refresh-interval-ms:86400000}")
    public void scheduledRefresh() {
        log.info("Scheduled DB credentials refresh from Secrets Manager (secret={})", secretName);
        refreshCredentials();
    }

    /**
     * Fetches the secret, parses the JSON payload, and injects R2DBC / Flyway
     * properties into the Spring Environment.
     */
    void refreshCredentials() {
        try {
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(
                    GetSecretValueRequest.builder().secretId(secretName).build());

            @SuppressWarnings("unchecked")
            Map<String, String> secret =
                    objectMapper.readValue(response.secretString(), Map.class);

            String host = secret.get("host");
            String port = secret.get("port");
            String dbName = secret.get("dbname");
            String username = secret.get("username");
            String password = secret.get("password");

            Map<String, Object> props = new HashMap<>();
            props.put("spring.r2dbc.url",
                    "r2dbc:postgresql://" + host + ":" + port + "/" + dbName);
            props.put("spring.r2dbc.username", username);
            props.put("spring.r2dbc.password", password);
            props.put("spring.flyway.url",
                    "jdbc:postgresql://" + host + ":" + port + "/" + dbName);
            props.put("spring.flyway.user", username);
            props.put("spring.flyway.password", password);

            MapPropertySource propertySource =
                    new MapPropertySource(PROPERTY_SOURCE_NAME, props);

            // Remove old version (if any) and insert at highest priority
            environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
            environment.getPropertySources().addFirst(propertySource);

            log.info("DB credentials refreshed successfully (host={}, db={})", host, dbName);

        } catch (Exception e) {
            log.error("Failed to refresh DB credentials from Secrets Manager: {}", e.getMessage(), e);
            throw new IllegalStateException(
                    "Cannot load database credentials from Secrets Manager (secret=" + secretName + ")", e);
        }
    }
}

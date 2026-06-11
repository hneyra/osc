package dev.osc.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

/**
 * OpenAPI contract test — issue #27.
 *
 * <p>Boots the full application and verifies SpringDoc serves a valid OpenAPI document at
 * {@code /v3/api-docs} (JSON) and {@code /v3/api-docs.yaml}, that every dynamic endpoint of the
 * record API appears in it, and that the Bearer (JWT) security scheme is documented. The
 * human-readable contract is published at {@code docs/contracts/openapi.yaml}; regenerate it from
 * a running instance with {@code curl localhost:8080/v3/api-docs.yaml > docs/contracts/openapi.yaml}.</p>
 *
 * <p>Requires Docker — skipped automatically where Docker is unavailable.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = OscApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("OpenAPI contract (/v3/api-docs)")
class OpenApiContractTest {

    @SuppressWarnings("resource")
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("osc_openapi_test")
                    .withUsername("osc")
                    .withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> POSTGRES.getJdbcUrl().replace("jdbc:", "r2dbc:"));
        registry.add("spring.r2dbc.username", POSTGRES::getUsername);
        registry.add("spring.r2dbc.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    WebTestClient http;

    @BeforeEach
    void setUp() {
        http = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(30))
                .build();
    }

    private static final String LIST = "$.paths.['/api/v1/data/{objectApiName}']";
    private static final String BY_ID = "$.paths.['/api/v1/data/{objectApiName}/{id}']";
    private static final String QUERY = "$.paths.['/api/v1/data/{objectApiName}/query']";

    @Test
    @DisplayName("/v3/api-docs is a valid OpenAPI document with the expected metadata + security scheme")
    void apiDocsIsValid() {
        http.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.openapi").exists()
                .jsonPath("$.info.title").isEqualTo("OSC Dynamic API")
                .jsonPath("$.components.securitySchemes.bearerAuth.scheme").isEqualTo("bearer")
                .jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").isEqualTo("JWT");
    }

    @Test
    @DisplayName("all six dynamic record endpoints are present in the spec")
    void allRecordEndpointsDocumented() {
        http.get().uri("/v3/api-docs")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath(LIST + ".get").exists()      // list
                .jsonPath(LIST + ".post").exists()     // create
                .jsonPath(BY_ID + ".get").exists()     // get by id
                .jsonPath(BY_ID + ".patch").exists()   // update
                .jsonPath(BY_ID + ".delete").exists()  // delete
                .jsonPath(QUERY + ".post").exists();   // SOQL-like query
    }

    @Test
    @DisplayName("/v3/api-docs.yaml is served (YAML contract endpoint)")
    void apiDocsYamlIsServed() {
        http.get().uri("/v3/api-docs.yaml")
                .exchange()
                .expectStatus().isOk();
    }
}

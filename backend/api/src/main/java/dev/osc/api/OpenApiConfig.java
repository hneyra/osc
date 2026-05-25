package dev.osc.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SpringDoc OpenAPI configuration — issue #27.
 * Spec served at /v3/api-docs (JSON) and /v3/api-docs.yaml (YAML).
 * Swagger UI at /swagger-ui.html (all profiles).
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI oscOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OSC Dynamic API")
                        .description("Runtime-configurable multi-tenant business application engine.")
                        .version("1.0.0-Phase2"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT Bearer token. In Phase 1, use X-Tenant-ID header for tenant resolution.")));
    }
}

package dev.osc.metadata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MetadataCacheProperties")
class MetadataCachePropertiesTest {

    @Test
    @DisplayName("defaults match the Phase 0 contract: 10 minutes / 10 000 entries")
    void defaults() {
        MetadataCacheProperties props = new MetadataCacheProperties();
        assertThat(props.getExpireAfterWrite()).isEqualTo(Duration.ofMinutes(10));
        assertThat(props.getMaximumSize()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("binds custom values from application.yml-style properties")
    void bindsFromProperties() {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "osc.metadata.cache.expire-after-write", "5m",
                "osc.metadata.cache.maximum-size", "500"));

        MetadataCacheProperties props = new Binder(source)
                .bind("osc.metadata.cache", MetadataCacheProperties.class)
                .get();

        assertThat(props.getExpireAfterWrite()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.getMaximumSize()).isEqualTo(500);
    }

    @Test
    @DisplayName("custom properties are accepted by the engine constructor")
    void engineAcceptsCustomProperties() {
        MetadataCacheProperties props = new MetadataCacheProperties();
        props.setMaximumSize(500);
        props.setExpireAfterWrite(Duration.ofMinutes(1));

        MetadataRepository emptyRepository = new MetadataRepository() {
            @Override
            public reactor.core.publisher.Mono<ObjectDefinition> findObject(java.util.UUID tenantId, String apiName) {
                return reactor.core.publisher.Mono.empty();
            }

            @Override
            public reactor.core.publisher.Flux<FieldDefinition> findFields(java.util.UUID tenantId, java.util.UUID objectId) {
                return reactor.core.publisher.Flux.empty();
            }
        };

        // Must build without error; runtime behaviour is covered by CaffeineMetadataEngineTest.
        assertThat(new CaffeineMetadataEngine(
                emptyRepository,
                new dev.osc.metadata.performance.FieldAccessCounter(),
                props)).isNotNull();
    }
}

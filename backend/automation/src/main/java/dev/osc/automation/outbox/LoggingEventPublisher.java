package dev.osc.automation.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Phase 5 stub — logs events to SLF4J. Phase 6 replaces with HTTP webhook delivery. */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public Mono<Void> publish(OutboxEvent event) {
        return Mono.fromRunnable(() ->
                log.info("event published tenant={} type={} aggregate={}",
                        event.tenantId(), event.eventType(), event.aggregateId()));
    }
}

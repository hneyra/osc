package dev.osc.integrations.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

public class DefaultOutboundHttpClient implements OutboundHttpClient {

    private static final Logger log = LoggerFactory.getLogger(DefaultOutboundHttpClient.class);

    private final DomainAllowlist allowlist;
    private final OutboundAuditLog auditLog;
    private final WebClientAdapter webClient;

    public DefaultOutboundHttpClient(
            DomainAllowlist allowlist,
            OutboundAuditLog auditLog,
            WebClientAdapter webClient) {
        this.allowlist  = allowlist;
        this.auditLog   = auditLog;
        this.webClient  = webClient;
    }

    @Override
    public Mono<Integer> post(String url, String body, Map<String, String> headers) {
        if (!allowlist.isAllowed(url)) {
            return Mono.error(new DomainNotAllowedException(url));
        }
        long start = Instant.now().toEpochMilli();
        return webClient.post(url, body, headers)
                .doOnNext(status -> {
                    long durationMs = Instant.now().toEpochMilli() - start;
                    auditLog.record(url, status, durationMs);
                    log.debug("Outbound POST {} → HTTP {}", url, status);
                });
    }
}

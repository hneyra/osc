package dev.osc.metadata.performance;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory counter for field access frequency per tenant and object.
 *
 * Key structure: tenantId::objectApiName::fieldApiName → hitCount
 *
 * Thread-safe via ConcurrentHashMap and AtomicLong.
 */
@Component
public class FieldAccessCounter {

    private final ConcurrentHashMap<String, AtomicLong> hits = new ConcurrentHashMap<>();

    /**
     * Records one access for the given field.
     */
    public void record(UUID tenantId, String objectApiName, String fieldApiName) {
        String key = buildKey(tenantId, objectApiName, fieldApiName);
        hits.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Returns the top-N most accessed fields for the given tenant and object,
     * sorted by hitCount descending.
     */
    public HotFieldReport topFields(UUID tenantId, String objectApiName, int limit) {
        String prefix = tenantId.toString() + "::" + objectApiName + "::";

        List<HotFieldReport.FieldHit> result = hits.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(e -> new HotFieldReport.FieldHit(
                        e.getKey().substring(prefix.length()),
                        e.getValue().get()
                ))
                .sorted(Comparator.comparingLong(HotFieldReport.FieldHit::hitCount).reversed())
                .limit(limit)
                .collect(Collectors.toList());

        return new HotFieldReport(result);
    }

    private String buildKey(UUID tenantId, String objectApiName, String fieldApiName) {
        return tenantId.toString() + "::" + objectApiName + "::" + fieldApiName;
    }
}

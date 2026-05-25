package dev.osc.integrations.http;

import java.util.ArrayList;
import java.util.List;

class InMemoryOutboundAuditLog implements OutboundAuditLog {
    record Entry(String url, int statusCode, long durationMs) {}
    final List<Entry> entries = new ArrayList<>();

    @Override
    public void record(String url, int statusCode, long durationMs) {
        entries.add(new Entry(url, statusCode, durationMs));
    }
}

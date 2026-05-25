package dev.osc.integrations.http;

public interface OutboundAuditLog {
    void record(String url, int statusCode, long durationMs);
}

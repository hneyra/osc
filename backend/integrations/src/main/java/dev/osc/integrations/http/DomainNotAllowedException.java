package dev.osc.integrations.http;

public class DomainNotAllowedException extends RuntimeException {
    public DomainNotAllowedException(String url) {
        super("Domain not in allowlist: " + url);
    }
}

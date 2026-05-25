package dev.osc.integrations.http;

import java.net.URI;
import java.util.Set;
import java.util.stream.Collectors;

/** Determines whether an outbound URL's host is in the tenant-configured allowlist. */
public class DomainAllowlist {

    private final Set<String> allowedDomains;

    public DomainAllowlist(Set<String> allowedDomains) {
        this.allowedDomains = allowedDomains.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowed(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            return allowedDomains.contains(host.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

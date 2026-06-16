package com.uptimecrew.multistate.clients;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityService.class);

    private final TenantIdentityClient client;

    public IdentityService(TenantIdentityClient client) {
        this.client = client;
    }

    @CircuitBreaker(name = "identity", fallbackMethod = "fallbackProfile")
    public IdentityProfile getProfile(String userId) {
        return client.getProfile(userId);
    }

    @SuppressWarnings("unused")
    private IdentityProfile fallbackProfile(String userId, Throwable t) {
        LOG.warn("identity breaker fallback for userId={} cause={}", userId, t.toString());
        return new IdentityProfile(userId, "", "unknown");
    }
}
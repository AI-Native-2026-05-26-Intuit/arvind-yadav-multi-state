package com.uptimecrew.multistate.clients;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

@HttpExchange
public interface TenantIdentityClient {

    @GetExchange("/identity/{userId}/profile")
    IdentityProfile getProfile(@PathVariable("userId") String userId);
}

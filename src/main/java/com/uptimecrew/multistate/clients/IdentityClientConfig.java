package com.uptimecrew.multistate.clients;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

@Configuration
public class IdentityClientConfig {

    @Bean
    TenantIdentityClient tenantIdentityClient(@Value("${identity.base-url}") String baseUrl) {
        RestClient restClient = RestClient.builder().baseUrl(baseUrl).build();
        HttpServiceProxyFactory factory = HttpServiceProxyFactory
                .builderFor(RestClientAdapter.create(restClient))
                .build();
        return factory.createClient(TenantIdentityClient.class);
    }
}

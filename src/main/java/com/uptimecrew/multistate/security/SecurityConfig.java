package com.uptimecrew.multistate.security;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

// Pattern reference for Task 1 (SecurityFilterChain).
//
// One @Bean SecurityFilterChain replaces the legacy WebSecurityConfigurerAdapter.
// The chain runs BEFORE DispatcherServlet, so unauthenticated requests never
// reach your controller — exactly the default-deny contract Spring Security 7
// promises once it's on the classpath.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)                // (1) turns @PreAuthorize on
public class SecurityConfig {

    private final RateLimitFilter rateLimitFilter;

    public SecurityConfig(RateLimitFilter rateLimitFilter) {
        this.rateLimitFilter = rateLimitFilter;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        http
            // (2) Stateless Bearer-only API; no session cookie -> no CSRF surface.
            //     If this app ever grows a cookie-based login, re-enable CSRF FIRST.
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/actuator/prometheus", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/v3/api-docs",
                                 "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // MCP SSE transport: /sse opens the event stream, /mcp/message is
                // the client->server JSON-RPC channel. Left unauthenticated so a
                // local LLM client (Claude Code) can attach during development.
                // In production this should sit behind a separate filter chain
                // with mTLS or a dedicated MCP-only bearer token.
                .requestMatchers("/sse", "/mcp/**").permitAll()
                // GraphQL + GraphiQL UI + SDL endpoint left open for local exploration.
                // The same production caveat as the MCP block applies: in production these
                // should sit behind a separate filter chain with a real bearer token.
                .requestMatchers("/graphql", "/graphiql/**", "/graphql/schema").permitAll()
                // W5 D5: smoke / SLO path — no JWT; metrics uri=/tenants/{tenantId}.
                .requestMatchers(HttpMethod.GET, "/tenants/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(jwt -> jwt
                .jwtAuthenticationConverter(jwtAuthenticationConverter())))
            // (3) Place the rate-limit filter AFTER the bearer-token filter so the
            //     JWT principal is already resolved when the bucket is looked up.
            .addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class);

        return http.build();
    }

    // Maps both `scope` (space-delimited claim) -> SCOPE_* authorities AND a
    // custom `roles` claim -> ROLE_* authorities. The combination is what the
    // controller's @PreAuthorize SpEL checks against.
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthorityPrefix("SCOPE_");
        scopes.setAuthoritiesClaimName("scope");

        JwtAuthenticationConverter conv = new JwtAuthenticationConverter();
        conv.setJwtGrantedAuthoritiesConverter((Jwt jwt) -> {
            Collection<GrantedAuthority> scopeAuths = scopes.convert(jwt);
            List<String> roles = jwt.getClaimAsStringList("roles");
            Stream<GrantedAuthority> roleAuths = (roles == null ? Stream.<String>empty() : roles.stream())
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r));
            return Stream.concat(scopeAuths.stream(), roleAuths).toList();
        });
        return conv;
    }
}

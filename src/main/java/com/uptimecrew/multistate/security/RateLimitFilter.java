package com.uptimecrew.multistate.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// In-memory bucket store is fine for this assignment; production would use
// bucket4j-redis so the limit is shared across instances (trade-off: one
// Redis round-trip per request vs. losing state on restart).
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentMap<String, Bucket> bucketsBySubject = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {

        String uri = req.getRequestURI();
        if (!uri.startsWith("/api/") || !uri.endsWith("/summary")) {
            chain.doFilter(req, res);
            return;
        }

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jat)) {
            chain.doFilter(req, res);
            return;
        }
        Jwt jwt = jat.getToken();
        String subject = jwt.getSubject();

        Bucket bucket = bucketsBySubject.computeIfAbsent(subject, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(10, Refill.intervally(10, Duration.ofMinutes(1))))
                .build());

        if (bucket.tryConsume(1)) {
            chain.doFilter(req, res);
        } else {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"rate_limited\"}");
        }
    }
}
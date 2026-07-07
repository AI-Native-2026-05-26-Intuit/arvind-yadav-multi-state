package com.uptimecrew.multistate.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

  @Mock StringRedisTemplate redis;
  @Mock ValueOperations<String, String> valueOps;

  IdempotencyService service;

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(valueOps);
    service = new IdempotencyService(redis);
  }

  @Test
  @DisplayName("returns cached body on cache hit")
  void returnsCachedBodyOnHit() {
    when(valueOps.get("idem:tenant:create:key-1"))
        .thenReturn("{\"id\":\"t-1\",\"status\":\"ACTIVE\"}");

    ResponseEntity<Map<String, String>> response =
        service.handle("key-1", "tenant:create", () -> ResponseEntity.status(HttpStatus.CREATED).build());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("id", "t-1");
  }

  @Test
  @DisplayName("returns 409 when another request holds the in-flight lock")
  void returns409OnInFlightConflict() {
    when(valueOps.get("idem:tenant:create:key-2")).thenReturn(null);
    when(valueOps.setIfAbsent(eq("idem:tenant:create:key-2"), eq("__in_flight__"), any(Duration.class)))
        .thenReturn(false);

    ResponseEntity<String> response =
        service.handle("key-2", "tenant:create", () -> ResponseEntity.ok("fresh"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  @DisplayName("executes work and caches result on miss")
  void executesWorkAndCachesOnMiss() {
    when(valueOps.get("idem:tenant:create:key-3")).thenReturn(null);
    when(valueOps.setIfAbsent(eq("idem:tenant:create:key-3"), eq("__in_flight__"), any(Duration.class)))
        .thenReturn(true);

    ResponseEntity<String> response =
        service.handle("key-3", "tenant:create", () -> ResponseEntity.ok("created"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo("created");
    verify(valueOps).set(eq("idem:tenant:create:key-3"), eq("\"created\""), any(Duration.class));
  }

  @Test
  @DisplayName("recomputes when cached body is unreadable JSON")
  void recomputesWhenCachedBodyUnreadable() {
    when(valueOps.get("idem:tenant:create:key-4")).thenReturn("not-json");
    when(valueOps.setIfAbsent(eq("idem:tenant:create:key-4"), eq("__in_flight__"), any(Duration.class)))
        .thenReturn(true);

    ResponseEntity<String> response =
        service.handle("key-4", "tenant:create", () -> ResponseEntity.ok("recomputed"));

    assertThat(response.getBody()).isEqualTo("recomputed");
  }

  @Test
  @DisplayName("treats in-flight marker as cache miss and acquires lock")
  void treatsInFlightMarkerAsMiss() {
    when(valueOps.get("idem:tenant:create:key-5")).thenReturn("__in_flight__");
    when(valueOps.setIfAbsent(eq("idem:tenant:create:key-5"), eq("__in_flight__"), any(Duration.class)))
        .thenReturn(true);

    ResponseEntity<String> response =
        service.handle("key-5", "tenant:create", () -> ResponseEntity.ok("fresh"));

    assertThat(response.getBody()).isEqualTo("fresh");
  }
}

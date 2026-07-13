package com.uptimecrew.multistate.llmproxy.cost;

import java.time.LocalDate;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class JedisRedisCostStore implements RedisCostStore {

    private final StringRedisTemplate redis;

    public JedisRedisCostStore(StringRedisTemplate redis) {
        this.redis = Objects.requireNonNull(redis);
    }

    @Override
    public void incrementTenantDay(String tenant, LocalDate day, long costUsdE5) {
        String key = "cost:" + tenant + ":" + day;
        redis.opsForHash().increment(key, "cost_usd_e5", costUsdE5);
    }
}

package com.uptimecrew.multistate.llmproxy.cost;

import java.time.LocalDate;

public interface RedisCostStore {

    void incrementTenantDay(String tenant, LocalDate day, long costUsdE5);
}

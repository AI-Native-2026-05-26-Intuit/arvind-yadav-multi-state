package com.uptimecrew.multistate.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * One day of work performed by a worker, attributed to a single jurisdiction.
 * The raw input to {@link com.uptimecrew.multistate.service.AllocationStrategy}:
 * a collection of WorkDays defines where a worker's time was spent over a period,
 * which in turn drives how income for that period is split.
 *
 * <p>{@code jurisdictionCode} is the {@link Jurisdiction#code()} the day is attributed to
 * (held by code rather than reference so WorkDays don't carry a full Jurisdiction graph).
 */
public final class WorkDay {

    private final String id;
    private final String workerId;
    private final String jurisdictionCode;
    private final LocalDate workedOn;

    public WorkDay(String id, String workerId, String jurisdictionCode, LocalDate workedOn) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workerId, "workerId");
        Objects.requireNonNull(jurisdictionCode, "jurisdictionCode");
        Objects.requireNonNull(workedOn, "workedOn");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (workerId.isBlank()) {
            throw new IllegalArgumentException("workerId must not be blank");
        }
        if (jurisdictionCode.isBlank()) {
            throw new IllegalArgumentException("jurisdictionCode must not be blank");
        }
        this.id = id;
        this.workerId = workerId;
        this.jurisdictionCode = jurisdictionCode;
        this.workedOn = workedOn;
    }

    public String id() {
        return id;
    }

    public String workerId() {
        return workerId;
    }

    public String jurisdictionCode() {
        return jurisdictionCode;
    }

    public LocalDate workedOn() {
        return workedOn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkDay other)) return false;
        return id.equals(other.id)
            && workerId.equals(other.workerId)
            && jurisdictionCode.equals(other.jurisdictionCode)
            && workedOn.equals(other.workedOn);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, workerId, jurisdictionCode, workedOn);
    }

    @Override
    public String toString() {
        return "WorkDay[id=" + id
            + ", workerId=" + workerId
            + ", jurisdictionCode=" + jurisdictionCode
            + ", workedOn=" + workedOn + "]";
    }
}

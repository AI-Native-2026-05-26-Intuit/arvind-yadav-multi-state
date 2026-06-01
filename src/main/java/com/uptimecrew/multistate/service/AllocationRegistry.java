package com.uptimecrew.multistate.service;

import com.uptimecrew.multistate.model.IncomeAllocation;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class AllocationRegistry {

    private final Map<String, IncomeAllocation> allocationsById;

    public AllocationRegistry(Collection<IncomeAllocation> allocations) {
        Objects.requireNonNull(allocations, "allocations");
        Map<String, IncomeAllocation> copy = new HashMap<>();
        for (IncomeAllocation allocation : allocations) {
            Objects.requireNonNull(allocation, "allocation");
            IncomeAllocation previous = copy.put(allocation.id(), allocation);
            if (previous != null) {
                throw new IllegalArgumentException(
                    "duplicate allocation id: " + allocation.id());
            }
        }
        this.allocationsById = Map.copyOf(copy);
    }

    public AllocationRegistry(Map<String, IncomeAllocation> allocations) {
        Objects.requireNonNull(allocations, "allocations");
        Map<String, IncomeAllocation> copy = new HashMap<>();
        for (Map.Entry<String, IncomeAllocation> entry : allocations.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "id");
            IncomeAllocation value = Objects.requireNonNull(entry.getValue(), "allocation");
            if (!key.equals(value.id())) {
                throw new IllegalArgumentException(
                    "map key " + key + " does not match allocation id " + value.id());
            }
            copy.put(key, value);
        }
        this.allocationsById = Map.copyOf(copy);
    }

    public int size() {
        return allocationsById.size();
    }

    public Optional<IncomeAllocation> findById(String id) {
        Objects.requireNonNull(id, "id");
        return Optional.ofNullable(allocationsById.get(id));
    }

    public List<IncomeAllocation> findByJurisdictionAbove(String jurisdictionCode, BigDecimal threshold) {
        Objects.requireNonNull(jurisdictionCode, "jurisdictionCode");
        Objects.requireNonNull(threshold, "threshold");
        return allocationsById.values().stream()
            .filter(allocation -> allocation.jurisdictionCode().equals(jurisdictionCode)
                && allocation.amount().compareTo(threshold) > 0)
            .sorted(Comparator.comparing(IncomeAllocation::amount).reversed()
                .thenComparing(IncomeAllocation::allocatedFor)
                .thenComparing(IncomeAllocation::id))
            .toList();
    }
}

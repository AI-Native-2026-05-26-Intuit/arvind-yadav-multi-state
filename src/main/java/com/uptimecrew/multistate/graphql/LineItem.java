package com.uptimecrew.multistate.graphql;

import com.uptimecrew.multistate.readmodel.TenantReadModel.EmbeddedAllocation;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * GraphQL projection of {@link EmbeddedAllocation} matching the
 * {@code LineItem { id, description, amount }} type in
 * {@code resources/graphql/schema.graphqls}.
 *
 * <p>{@code description} surfaces the underlying jurisdiction code (the
 * embedded allocation's natural label) and {@code amount} is the
 * {@code BigDecimal} amount coerced to a {@code double} for the GraphQL
 * {@code Float} type. We keep the persisted scale-2 {@code HALF_UP} rounding
 * by reading {@code .doubleValue()} off the already-normalised {@code BigDecimal}
 * — no money math happens here, this is just a wire conversion.
 */
public record LineItem(String id, String description, double amount) {

    public LineItem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
    }

    public static LineItem from(EmbeddedAllocation a) {
        return new LineItem(
                a.getId(),
                a.getJurisdictionCode(),
                a.getAmount().setScale(2, RoundingMode.HALF_UP).doubleValue());
    }
}

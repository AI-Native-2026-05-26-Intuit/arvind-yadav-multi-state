package com.uptimecrew.multistate.model;

/**
 * The level of government a {@link Jurisdiction} represents. Used to disambiguate
 * authorities that share a code prefix and to drive level-specific tax rules.
 */
public enum JurisdictionKind {
    FEDERAL,
    STATE,
    COUNTY,
    CITY
}

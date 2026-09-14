package com.grandvista.roomservice.entity;

public enum PricingRuleType {
    /** Applies to nights that fall inside [startDate, endDate], e.g. festive season. */
    SEASONAL,
    /** Applies when hotel occupancy for the requested stay reaches minOccupancyPercent. */
    OCCUPANCY
}

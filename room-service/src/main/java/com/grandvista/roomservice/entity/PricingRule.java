package com.grandvista.roomservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Administrator-configured pricing adjustment. Keeping multipliers in the database
 * (instead of constants in code) lets admins change pricing without redeploying.
 */
@Entity
@Table(name = "pricing_rules")
public class PricingRule extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 20)
    private PricingRuleType ruleType;

    /** e.g. 1.30 = +30 %. */
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal multiplier;

    /** SEASONAL only. */
    @Column(name = "start_date")
    private LocalDate startDate;

    /** SEASONAL only (inclusive). */
    @Column(name = "end_date")
    private LocalDate endDate;

    /** OCCUPANCY only. */
    @Column(name = "min_occupancy_percent")
    private Integer minOccupancyPercent;

    /** Optional scope: null means "all hotels". Plain id inside the same database. */
    @Column(name = "hotel_id")
    private Long hotelId;

    /** Optional scope: null means "all room types". */
    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", length = 20)
    private RoomType roomType;

    @Column(nullable = false)
    private boolean active = true;

    protected PricingRule() {
        // required by JPA
    }

    public PricingRule(String name, PricingRuleType ruleType, BigDecimal multiplier, LocalDate startDate,
                       LocalDate endDate, Integer minOccupancyPercent, Long hotelId, RoomType roomType, boolean active) {
        update(name, ruleType, multiplier, startDate, endDate, minOccupancyPercent, hotelId, roomType, active);
    }

    public final void update(String name, PricingRuleType ruleType, BigDecimal multiplier, LocalDate startDate,
                             LocalDate endDate, Integer minOccupancyPercent, Long hotelId, RoomType roomType,
                             boolean active) {
        this.name = name;
        this.ruleType = ruleType;
        this.multiplier = multiplier;
        this.startDate = startDate;
        this.endDate = endDate;
        this.minOccupancyPercent = minOccupancyPercent;
        this.hotelId = hotelId;
        this.roomType = roomType;
        this.active = active;
    }

    /** True when the rule's hotel/room-type scope covers the given room. */
    public boolean appliesTo(Long roomHotelId, RoomType type) {
        return active
                && (hotelId == null || hotelId.equals(roomHotelId))
                && (roomType == null || roomType == type);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public PricingRuleType getRuleType() {
        return ruleType;
    }

    public BigDecimal getMultiplier() {
        return multiplier;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Integer getMinOccupancyPercent() {
        return minOccupancyPercent;
    }

    public Long getHotelId() {
        return hotelId;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public boolean isActive() {
        return active;
    }
}

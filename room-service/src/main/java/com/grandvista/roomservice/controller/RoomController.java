package com.grandvista.roomservice.controller;

import com.grandvista.roomservice.dto.AvailabilityResponse;
import com.grandvista.roomservice.dto.HoldResponse;
import com.grandvista.roomservice.dto.InventorySummaryResponse;
import com.grandvista.roomservice.dto.PriceQuoteResponse;
import com.grandvista.roomservice.dto.PriceUpdateRequest;
import com.grandvista.roomservice.dto.RoomRequest;
import com.grandvista.roomservice.dto.RoomResponse;
import com.grandvista.roomservice.dto.RoomSearchCriteria;
import com.grandvista.roomservice.dto.RoomStatusUpdateRequest;
import com.grandvista.roomservice.entity.RoomType;
import com.grandvista.roomservice.service.InventoryService;
import com.grandvista.roomservice.service.PricingService;
import com.grandvista.roomservice.service.RoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/rooms")
@Tag(name = "Rooms")
public class RoomController {

    private final RoomService roomService;
    private final InventoryService inventoryService;
    private final PricingService pricingService;

    public RoomController(RoomService roomService, InventoryService inventoryService, PricingService pricingService) {
        this.roomService = roomService;
        this.inventoryService = inventoryService;
        this.pricingService = pricingService;
    }

    // ------------------------------------------------------------------ public

    @GetMapping
    @Operation(summary = "Search bookable rooms; with checkIn+checkOut only rooms free for the whole stay are returned")
    public List<RoomResponse> searchRooms(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Long hotelId,
            @RequestParam(required = false) RoomType roomType,
            @RequestParam(required = false) Integer guests,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {
        return roomService.searchRooms(new RoomSearchCriteria(city, hotelId, roomType, guests, maxPrice, checkIn, checkOut));
    }

    @GetMapping("/{roomId}")
    @Operation(summary = "Room details")
    public RoomResponse getRoom(@PathVariable Long roomId) {
        return roomService.getRoom(roomId);
    }

    @GetMapping("/{roomId}/availability")
    @Operation(summary = "Check whether the room is free for a date range")
    public AvailabilityResponse checkAvailability(
            @PathVariable Long roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {
        return inventoryService.checkAvailability(roomId, checkIn, checkOut);
    }

    @GetMapping("/{roomId}/price")
    @Operation(summary = "Dynamic price quote with base price, applied rules and final total")
    public PriceQuoteResponse quotePrice(
            @PathVariable Long roomId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut) {
        return pricingService.quote(roomId, checkIn, checkOut);
    }

    // ------------------------------------------------------------------ admin

    @GetMapping("/all")
    @Operation(summary = "All rooms including maintenance/inactive (ADMIN)")
    public List<RoomResponse> listAllRooms() {
        return roomService.listAllRooms();
    }

    @GetMapping("/inventory-summary")
    @Operation(summary = "Per-hotel inventory and tonight's occupancy (ADMIN)")
    public List<InventorySummaryResponse> inventorySummary() {
        return inventoryService.inventorySummary();
    }

    @GetMapping("/{roomId}/holds")
    @Operation(summary = "Current and upcoming reservations of a room (ADMIN)")
    public List<HoldResponse> upcomingHolds(@PathVariable Long roomId) {
        return inventoryService.upcomingHoldsForRoom(roomId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a room (ADMIN)")
    public RoomResponse createRoom(@Valid @RequestBody RoomRequest request) {
        return roomService.createRoom(request);
    }

    @PutMapping("/{roomId}")
    @Operation(summary = "Update a room (ADMIN)")
    public RoomResponse updateRoom(@PathVariable Long roomId, @Valid @RequestBody RoomRequest request) {
        return roomService.updateRoom(roomId, request);
    }

    @PatchMapping("/{roomId}/price")
    @Operation(summary = "Change the base nightly price (ADMIN)")
    public RoomResponse updatePrice(@PathVariable Long roomId, @Valid @RequestBody PriceUpdateRequest request) {
        return roomService.updateBasePrice(roomId, request);
    }

    @PatchMapping("/{roomId}/status")
    @Operation(summary = "Set ACTIVE / MAINTENANCE / INACTIVE (ADMIN)")
    public RoomResponse updateStatus(@PathVariable Long roomId, @Valid @RequestBody RoomStatusUpdateRequest request) {
        return roomService.updateStatus(roomId, request);
    }

    @DeleteMapping("/{roomId}")
    @Operation(summary = "Deactivate (soft delete) a room (ADMIN)")
    public RoomResponse deactivateRoom(@PathVariable Long roomId) {
        return roomService.deactivateRoom(roomId);
    }
}

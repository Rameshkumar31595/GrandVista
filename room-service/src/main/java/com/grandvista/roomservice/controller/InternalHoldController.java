package com.grandvista.roomservice.controller;

import com.grandvista.roomservice.dto.CreateHoldRequest;
import com.grandvista.roomservice.dto.HoldResponse;
import com.grandvista.roomservice.service.InventoryService;
import com.grandvista.roomservice.util.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API used by the Booking Service (not routed by the API Gateway).
 * Requires the caller's user JWT plus the X-Internal-Api-Key header.
 */
@RestController
@RequestMapping("/internal/holds")
@Tag(name = "Internal - reservation holds (Booking Service only)")
public class InternalHoldController {

    private final InventoryService inventoryService;

    public InternalHoldController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Verify availability, calculate price and hold the room")
    public HoldResponse createHold(@Valid @RequestBody CreateHoldRequest request, @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.createHold(request, AuthenticatedUser.from(jwt));
    }

    @PostMapping("/{holdId}/confirm")
    @Operation(summary = "Confirm the hold after successful payment")
    public HoldResponse confirmHold(@PathVariable Long holdId, @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.confirmHold(holdId, AuthenticatedUser.from(jwt));
    }

    @PostMapping("/{holdId}/release")
    @Operation(summary = "Release the hold (payment failed or booking cancelled)")
    public HoldResponse releaseHold(@PathVariable Long holdId, @AuthenticationPrincipal Jwt jwt) {
        return inventoryService.releaseHold(holdId, AuthenticatedUser.from(jwt));
    }
}

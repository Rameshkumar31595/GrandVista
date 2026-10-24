package com.grandvista.roomservice.controller;

import com.grandvista.roomservice.dto.CreateHoldRequest;
import com.grandvista.roomservice.dto.HoldResponse;
import com.grandvista.roomservice.service.InventoryService;
import com.grandvista.roomservice.util.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service API used by the Booking Service (not routed by the API Gateway).
 * Requires the X-Internal-Api-Key header, plus the caller's user JWT when acting for a guest. Confirm and
 * release may also be called by the trusted service itself (recovery job); creating a hold needs a guest.
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
    public HoldResponse createHold(@Valid @RequestBody CreateHoldRequest request, Authentication authentication) {
        return inventoryService.createHold(request, AuthenticatedUser.from(authentication));
    }

    @PostMapping("/{holdId}/confirm")
    @Operation(summary = "Confirm the hold after successful payment")
    public HoldResponse confirmHold(@PathVariable Long holdId, Authentication authentication) {
        return inventoryService.confirmHold(holdId, AuthenticatedUser.from(authentication));
    }

    @PostMapping("/{holdId}/release")
    @Operation(summary = "Release the hold (payment failed or booking cancelled)")
    public HoldResponse releaseHold(@PathVariable Long holdId, Authentication authentication) {
        return inventoryService.releaseHold(holdId, AuthenticatedUser.from(authentication));
    }
}

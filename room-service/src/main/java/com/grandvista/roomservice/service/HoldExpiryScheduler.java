package com.grandvista.roomservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping only: expired HELD rows already stop blocking availability through the
 * {@code expiresAt > now} condition in queries. This job just marks them EXPIRED so the
 * data in pgAdmin reflects reality.
 */
@Component
public class HoldExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryScheduler.class);

    private final InventoryService inventoryService;

    public HoldExpiryScheduler(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void expireStaleHolds() {
        int expired = inventoryService.expireStaleHolds();
        if (expired > 0) {
            log.info("Marked {} unpaid reservation hold(s) as EXPIRED", expired);
        }
    }
}

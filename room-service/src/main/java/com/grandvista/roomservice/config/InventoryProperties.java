package com.grandvista.roomservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "grandvista.inventory")
public record InventoryProperties(int holdMinutes, int maxStayNights, boolean seedDemoData) {
}

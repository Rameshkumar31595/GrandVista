package com.grandvista.apigateway.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Free-tier keep-warm settings, bound from {@code grandvista.keep-warm.*} (set only by the cloud profile).
 *
 * @param urls         comma separated health URLs of Eureka and the business services; empty = feature off
 * @param interval     how often to ping while users are active (must stay below the host's 15-minute idle limit)
 * @param activeWindow pings continue this long after the last real request through the gateway, then stop
 */
@ConfigurationProperties(prefix = "grandvista.keep-warm")
public record KeepWarmProperties(@DefaultValue("") String urls,
                                 @DefaultValue("10m") Duration interval,
                                 @DefaultValue("20m") Duration activeWindow) {

    public List<String> urlList() {
        return Arrays.stream(urls.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}

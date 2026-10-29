package com.grandvista.apigateway;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.context.annotation.Bean;

/**
 * Load-balancer configuration for the AUTH-SERVICE child context used by
 * AuthRouteRetryIntegrationTest: hands out the instances the test's static controls describe.
 * Not component-scanned (@TestConfiguration); wired only via @LoadBalancerClient(configuration=...).
 */
@TestConfiguration(proxyBeanMethods = false)
public class StubAuthServiceLoadBalancerConfig {

    @Bean
    ServiceInstanceListSupplier authServiceInstanceListSupplier() {
        return AuthRouteRetryIntegrationTest.controllableSupplier();
    }
}

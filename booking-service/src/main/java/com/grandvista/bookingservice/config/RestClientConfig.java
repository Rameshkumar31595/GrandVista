package com.grandvista.bookingservice.config;

import org.springframework.cloud.client.loadbalancer.DeferringLoadBalancerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Service-to-service HTTP clients.
 * <p>
 * Each client carries Spring Cloud LoadBalancer's interceptor (the same one {@code @LoadBalanced} adds):
 * the host part of the URL ({@code ROOM-SERVICE}) is looked up in the Eureka registry and replaced with one
 * of the registered instances, chosen round-robin. Starting a second Room Service instance on another port
 * therefore spreads Booking Service calls across both without any code change.
 * <p>
 * The interceptor is attached to these two clients only. A shared {@code @LoadBalanced RestClient.Builder}
 * bean is deliberately NOT declared: it would replace Spring Boot's default builder, which the Eureka client
 * itself uses to reach {@code http://localhost:9000} - and "localhost" is not a registered service name, so
 * registration would fail.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient roomServiceRestClient(DeferringLoadBalancerInterceptor loadBalancerInterceptor,
                                     ClientProperties properties) {
        return loadBalancedClient(properties.roomServiceUrl(), loadBalancerInterceptor, properties);
    }

    @Bean
    RestClient paymentServiceRestClient(DeferringLoadBalancerInterceptor loadBalancerInterceptor,
                                        ClientProperties properties) {
        return loadBalancedClient(properties.paymentServiceUrl(), loadBalancerInterceptor, properties);
    }

    private static RestClient loadBalancedClient(String serviceUrl, DeferringLoadBalancerInterceptor interceptor,
                                                 ClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        return RestClient.builder()
                .baseUrl(serviceUrl)            // e.g. http://ROOM-SERVICE
                .requestFactory(requestFactory)
                .requestInterceptor(interceptor)
                .build();
    }
}

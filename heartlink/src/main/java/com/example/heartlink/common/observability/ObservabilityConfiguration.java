package com.example.heartlink.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {
    @Bean
    EventPublisherPort eventPublisherPort(ApplicationEventPublisher publisher) {
        return publisher::publishEvent;
    }

    @Bean
    MetricPort metricPort(MeterRegistry registry) {
        return (event, outcome) -> io.micrometer.core.instrument.Counter.builder("heartlink.events")
                .tag("event", event.name().toLowerCase(java.util.Locale.ROOT))
                .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                .register(registry)
                .increment();
    }
}

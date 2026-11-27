package com.example.heartlink.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityConfigurationTests {
    private final ObservabilityConfiguration configuration = new ObservabilityConfiguration();

    @Test
    void metricPortUsesOnlyClosedEventAndOutcomeLabels() {
        var registry = new SimpleMeterRegistry();
        MetricPort port = configuration.metricPort(registry);

        port.increment(MetricPort.Event.NOTIFICATION_CREATED, MetricPort.Outcome.SUCCESS);

        assertThat(registry.get("heartlink.events")
                .tag("event", "notification_created")
                .tag("outcome", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters()).hasSize(1);
    }

    @Test
    void eventPublisherPortForwardsApplicationEvents() {
        var published = new ArrayList<Object>();
        ApplicationEventPublisher springPublisher = published::add;
        EventPublisherPort port = configuration.eventPublisherPort(springPublisher);
        Object event = new Object();

        port.publish(event);

        assertThat(published).containsExactly(event);
    }
}

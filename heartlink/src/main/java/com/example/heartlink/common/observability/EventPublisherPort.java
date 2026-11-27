package com.example.heartlink.common.observability;

/** Application event boundary; domain services do not depend on the Spring event bus. */
public interface EventPublisherPort {
    void publish(Object event);
}

package com.example.heartlink.common.observability;

/** Metrics boundary with a closed set of event names and outcomes to prevent sensitive/high-cardinality tags. */
public interface MetricPort {
    void increment(Event event, Outcome outcome);

    enum Event {
        NOTIFICATION_CREATED,
        NOTIFICATION_DELIVERED,
        NOTIFICATION_DELIVERY_FAILED
    }

    enum Outcome {
        SUCCESS,
        FAILURE
    }
}

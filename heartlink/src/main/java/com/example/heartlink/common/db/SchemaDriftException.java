package com.example.heartlink.common.db;

import java.util.List;

/**
 * Thrown during startup or guarded baseline verification when the live PostgreSQL
 * catalog diverges from Heartlink's canonical Flyway schema contract.
 */
public class SchemaDriftException extends IllegalStateException {

    private final List<String> violations;

    public SchemaDriftException(String phase, List<String> violations) {
        super("[SCHEMA-DRIFT] " + phase + " failed with " + violations.size()
                + " violation(s): " + String.join("; ", violations));
        this.violations = List.copyOf(violations);
    }

    public List<String> getViolations() {
        return violations;
    }
}


package com.example.heartlink.common.db;

import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Coordinates guarded Phase 1 snapshot baselining, startup schema-drift checks,
 * and strict blocking of destructive demo seeding outside local dev profiles.
 */
@Configuration
public class DatabaseMigrationAndSeedConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationAndSeedConfig.class);

    private static final Set<String> PROTECTED_PROFILES = Set.of("release", "prod", "production", "staging");
    private static final Set<String> DEMO_ALLOWED_PROFILES = Set.of("dev", "local");

    @Bean
    public SchemaDriftValidator schemaDriftValidator() {
        return new SchemaDriftValidator();
    }

    /**
     * Fails fast before DataSource/Flyway execution if release/prod profiles are combined
     * with destructive demo seed, SQL init scripts, auto-DDL, or blind baseline-on-migrate.
     */
    @Bean
    public InitializingBean databaseEnvironmentSafetyGuard(Environment env) {
        return () -> validateEnvironmentSafety(env);
    }

    @Bean
    public FlywayMigrationStrategy guardedFlywayMigrationStrategy(
            DataSource dataSource,
            SchemaDriftValidator driftValidator,
            Environment env) {
        return flyway -> {
            validateEnvironmentSafety(env);

            boolean guardedBaselineEnabled = env.getProperty(
                    "app.db.guarded-baseline-enabled", Boolean.class, false);
            boolean blindBaselineOnMigrate = env.getProperty(
                    "spring.flyway.baseline-on-migrate", Boolean.class, false);

            if (blindBaselineOnMigrate) {
                throw new IllegalStateException(
                        "[DB-UPGRADE-GUARD] Blind spring.flyway.baseline-on-migrate=true is forbidden. "
                                + "Use app.db.guarded-baseline-enabled=true so the Phase 1 schema is audited before baselining.");
            }

            if (guardedBaselineEnabled
                    && !driftValidator.hasFlywayHistoryTable(dataSource)
                    && driftValidator.hasNonEmptyPublicSchema(dataSource)) {
                log.info("Auditing existing non-empty database against Phase 1 V1 baseline contract...");
                driftValidator.verifyPhase1BaselineContract(dataSource);
                log.info("Pre-baseline Phase 1 schema audit passed; recording Flyway baseline at version {}.",
                        flyway.getConfiguration().getBaselineVersion());
                flyway.baseline();
            }

            flyway.migrate();
        };
    }

    /**
     * Runs after EntityManagerFactory (so Hibernate ddl-auto=validate runs first),
     * enforcing full constraint/index drift checks and optional dev-only demo seeding.
     */
    @Bean
    public SmartInitializingSingleton postStartupSchemaAndSeedInitializer(
            EntityManagerFactory entityManagerFactory,
            DataSource dataSource,
            SchemaDriftValidator driftValidator,
            Environment env) {
        return () -> {
            Objects.requireNonNull(entityManagerFactory, "EntityManagerFactory is required");
            boolean driftCheckEnabled = env.getProperty("app.db.drift-check-enabled", Boolean.class, true);
            String ddlAuto = env.getProperty("spring.jpa.hibernate.ddl-auto", "validate").trim().toLowerCase(Locale.ROOT);

            if (driftCheckEnabled && ("validate".equals(ddlAuto) || "none".equals(ddlAuto))) {
                driftValidator.verifyPostMigrationContract(dataSource);
            }

            boolean demoSeedEnabled = env.getProperty("app.seed.demo-enabled", Boolean.class, false);
            if (demoSeedEnabled) {
                executeGuardedDemoSeed(dataSource, env);
            }
        };
    }

    public static void validateEnvironmentSafety(Environment env) {
        Set<String> activeProfiles = Arrays.stream(env.getActiveProfiles())
                .map(p -> p.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        boolean isProtected = activeProfiles.stream().anyMatch(PROTECTED_PROFILES::contains);
        boolean demoSeedEnabled = env.getProperty("app.seed.demo-enabled", Boolean.class, false);
        String ddlAuto = env.getProperty("spring.jpa.hibernate.ddl-auto", "validate").trim().toLowerCase(Locale.ROOT);
        String sqlInitMode = env.getProperty("spring.sql.init.mode", "never").trim().toLowerCase(Locale.ROOT);
        String dataLocations = env.getProperty("spring.sql.init.data-locations", "").trim();

        if (isProtected) {
            if (demoSeedEnabled) {
                throw new IllegalStateException(
                        "[SEED-GUARD] Refusing startup: app.seed.demo-enabled=true is forbidden in protected profile(s) "
                                + activeProfiles + " because seed.sql runs TRUNCATE CASCADE.");
            }
            if (!"never".equals(sqlInitMode) && (!"embedded".equals(sqlInitMode) || !dataLocations.isEmpty())) {
                throw new IllegalStateException(
                        "[SEED-GUARD] Refusing startup: spring.sql.init.mode='" + sqlInitMode
                                + "' is forbidden in protected profile(s) " + activeProfiles + ".");
            }
            if (!dataLocations.isEmpty()) {
                throw new IllegalStateException(
                        "[SEED-GUARD] Refusing startup: spring.sql.init.data-locations is forbidden in protected profile(s) "
                                + activeProfiles + ".");
            }
            if (!"validate".equals(ddlAuto)) {
                throw new IllegalStateException(
                        "[DB-UPGRADE-GUARD] Refusing startup: spring.jpa.hibernate.ddl-auto must be 'validate' in protected profile(s) "
                                + activeProfiles + ", actual='" + ddlAuto + "'.");
            }
        }

        if (demoSeedEnabled) {
            boolean hasDevProfile = activeProfiles.stream().anyMatch(DEMO_ALLOWED_PROFILES::contains);
            if (!hasDevProfile) {
                throw new IllegalStateException(
                        "[SEED-GUARD] Destructive demo seed (TRUNCATE CASCADE) requires explicit 'dev' or 'local' profile, active="
                                + activeProfiles);
            }
        }
    }

    private void executeGuardedDemoSeed(DataSource dataSource, Environment env) {
        validateEnvironmentSafety(env);
        String scriptLocation = env.getProperty("app.seed.demo-script", "seed/seed.sql");
        Path scriptPath = Path.of(scriptLocation.replaceFirst("^file:", ""));
        if (!Files.exists(scriptPath)) {
            throw new IllegalStateException("[SEED-GUARD] Demo seed script not found at: " + scriptPath.toAbsolutePath());
        }
        String rawSql;
        try {
            rawSql = Files.readString(scriptPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("[SEED-GUARD] Failed to read demo seed script: " + scriptPath, e);
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET SESSION heartlink.allow_demo_seed = 'true'");
            try {
                stmt.execute(rawSql);
            } finally {
                stmt.execute("RESET heartlink.allow_demo_seed");
            }
            log.warn("[SEED-GUARD] Executed destructive demo seed from {} in dev/local profile.", scriptPath);
        } catch (SQLException e) {
            throw new IllegalStateException("[SEED-GUARD] Demo seed execution failed", e);
        }
    }

    private static final class Objects {
        private static <T> T requireNonNull(T value, String message) {
            if (value == null) {
                throw new IllegalStateException(message);
            }
            return value;
        }
    }
}


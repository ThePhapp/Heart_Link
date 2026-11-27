package com.example.heartlink.common.db;

import com.example.heartlink.HeartlinkApplication;
import org.flywaydb.core.api.FlywayException;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for T04 against disposable PostgreSQL 16 (docker-compose.test.yml on port 55432):
 *  1. Guarded upgrade of a pre-Flyway Phase 1 snapshot preserving IDs, row counts, FKs, and indexes.
 *  2. Pre-check data audit rejecting dirty self-swipe / reversed-match rows without mutating data.
 *  3. Negative schema-drift tests (missing index, missing FK, altered column type/nullability, missing V2 check).
 *  4. SQL-level and Spring release-profile guards blocking destructive TRUNCATE demo seed.
 */
class DatabaseUpgradeAndDriftIT {

    private static final String ADMIN_URL = "jdbc:postgresql://localhost:55432/postgres";
    private static final String DB_HOST_PREFIX = "jdbc:postgresql://localhost:55432/";
    private static final String DB_USER = "postgres";
    private static final String DB_PASS = "test";
    private static final String TEST_JWT_SECRET = "t04-upgrade-drift-test-secret-32bytes!!";

    private static final List<String> CORE_TABLES = List.of(
            "users", "refresh_tokens", "swipes", "matches", "messages", "notifications"
    );

    private static final List<String> CREATED_DATABASES = new ArrayList<>();

    @BeforeAll
    static void verifyTestPostgresReachable() throws SQLException {
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, DB_USER, DB_PASS)) {
            assertThat(conn.isValid(5)).isTrue();
        }
    }

    @AfterAll
    static void dropDisposableDatabases() throws SQLException {
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, DB_USER, DB_PASS);
             Statement stmt = conn.createStatement()) {
            for (String db : CREATED_DATABASES) {
                stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + db + "' AND pid <> pg_backend_pid()");
                stmt.execute("DROP DATABASE IF EXISTS " + db);
            }
        }
    }

    @Test
    void upgradesLegacyPhase1SnapshotPreservingIdsRowCountsForeignKeysAndIndexes() throws Exception {
        String dbName = createFreshDatabase("heartlink_t04_legacy_ok");
        seedLegacyPhase1SnapshotWithHibernateHashFkNames(dbName);

        SnapshotState before = captureSnapshotState(dbName);
        assertThat(before.rowCounts()).isEqualTo(Map.of(
                "users", 4L,
                "refresh_tokens", 2L,
                "swipes", 4L,
                "matches", 2L,
                "messages", 3L,
                "notifications", 4L
        ));
        assertThat(before.idsByTable().get("users")).containsExactly(10L, 20L, 35L, 50L);

        // 1. Without guarded-baseline-enabled, startup MUST refuse to touch the unbaselined non-empty DB
        assertThatThrownBy(() -> bootApp(dbName, Map.of(
                "app.db.guarded-baseline-enabled", "false"
        )).close()).hasRootCauseInstanceOf(FlywayException.class)
                .rootCause()
                .hasMessageContaining("Found non-empty schema(s) \"public\" but no schema history table");
        assertThat(captureSnapshotState(dbName)).isEqualTo(before);

        // 2. With guarded-baseline-enabled=true, pre-audit passes -> baselines V1 -> migrates V2 -> Hibernate validate + drift check pass
        try (ConfigurableApplicationContext ctx = bootApp(dbName, Map.of(
                "app.db.guarded-baseline-enabled", "true"
        ))) {
            assertThat(ctx.isRunning()).isTrue();
        }

        SnapshotState after = captureSnapshotState(dbName);
        assertThat(after.rowCounts()).isEqualTo(before.rowCounts());
        assertThat(after.idsByTable()).isEqualTo(before.idsByTable());
        assertThat(after.foreignKeys()).isEqualTo(before.foreignKeys());
        assertThat(after.indexes()).isEqualTo(before.indexes());

        withJdbc(dbName, jdbc -> {
            List<String> versions = jdbc.queryForList(
                    "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                    String.class);
            assertThat(versions).containsExactly("1", "2");

            // Verify V2 constraints actively block self-swipe and non-canonical match pairs
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO swipes (swiper_id, target_id, action, created_at) VALUES (10, 10, 'LIKE', NOW())"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_swipes_no_self");

            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO matches (user_a_id, user_b_id, status, created_at) VALUES (35, 10, 'ACTIVE', NOW())"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_matches_canonical_pair");

            // Subsequent normal restarts with guarded-baseline-enabled=false succeed cleanly
        });

        try (ConfigurableApplicationContext restartCtx = bootApp(dbName, Map.of(
                "app.db.guarded-baseline-enabled", "false"
        ))) {
            assertThat(restartCtx.isRunning()).isTrue();
        }
    }

    @Test
    void abortsV2UpgradeWithoutMutatingDataWhenSnapshotContainsDirtyMatchOrSelfSwipe() throws Exception {
        String dbName = createFreshDatabase("heartlink_t04_dirty_data");
        seedLegacyPhase1SnapshotWithHibernateHashFkNames(dbName);
        withJdbc(dbName, jdbc -> jdbc.update(
                "INSERT INTO matches (id, user_a_id, user_b_id, status, created_at) OVERRIDING SYSTEM VALUE VALUES (99, 50, 10, 'ACTIVE', NOW())"
        ));

        SnapshotState beforeAttempt = captureSnapshotState(dbName);

        assertThatThrownBy(() -> bootApp(dbName, Map.of(
                "app.db.guarded-baseline-enabled", "true"
        )).close()).rootCause()
                .hasMessageContaining("[T04-DATA-AUDIT]")
                .hasMessageContaining("chk_matches_canonical_pair");

        // Data in matches and all other tables must remain 100% untouched (no silent delete/update)
        SnapshotState afterFailedAttempt = captureSnapshotState(dbName);
        assertThat(afterFailedAttempt.rowCounts()).isEqualTo(beforeAttempt.rowCounts());
        assertThat(afterFailedAttempt.idsByTable()).isEqualTo(beforeAttempt.idsByTable());
    }

    @Test
    void negativeDriftTestsFailStartupClearlyForPreBaselineAndPostMigrationDrift() throws Exception {
        // Case 1: Pre-baseline legacy snapshot missing a secondary index -> guarded baseline refuses before creating flyway_schema_history
        String driftedLegacyDb = createFreshDatabase("heartlink_t04_drift_legacy");
        seedLegacyPhase1SnapshotWithHibernateHashFkNames(driftedLegacyDb);
        withJdbc(driftedLegacyDb, jdbc -> jdbc.execute("DROP INDEX idx_messages_match_id"));

        assertThatThrownBy(() -> bootApp(driftedLegacyDb, Map.of(
                "app.db.guarded-baseline-enabled", "true"
        )).close()).hasRootCauseInstanceOf(SchemaDriftException.class)
                .rootCause()
                .hasMessageContaining("[SCHEMA-DRIFT]")
                .hasMessageContaining("messages.idx_messages_match_id");

        withJdbc(driftedLegacyDb, jdbc -> {
            Boolean historyExists = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM information_schema.tables
                         WHERE table_schema = 'public' AND table_name = 'flyway_schema_history'
                    )
                    """, Boolean.class);
            assertThat(historyExists).isFalse();
        });

        // Case 2: Post-migration constraint/index/nullability drift on a V2 database -> startup fails clearly with SchemaDriftException
        String postMigrationDriftDb = createFreshDatabase("heartlink_t04_drift_post");
        try (ConfigurableApplicationContext initCtx = bootApp(postMigrationDriftDb, Map.of())) {
            assertThat(initCtx.isRunning()).isTrue();
        }
        withJdbc(postMigrationDriftDb, jdbc -> {
            jdbc.execute("DROP INDEX idx_notifications_user_id");
            jdbc.execute("ALTER TABLE swipes DROP CONSTRAINT chk_swipes_no_self");
            jdbc.execute("ALTER TABLE users ALTER COLUMN name DROP NOT NULL");
        });

        assertThatThrownBy(() -> bootApp(postMigrationDriftDb, Map.of()).close())
                .isInstanceOf(SchemaDriftException.class)
                .hasMessageContaining("notifications.idx_notifications_user_id")
                .hasMessageContaining("swipes.chk_swipes_no_self")
                .hasMessageContaining("users.name");

        // Case 3: Missing/mistyped column caught by Hibernate ddl-auto=validate with SchemaManagementException
        String hibernateDriftDb = createFreshDatabase("heartlink_t04_drift_col");
        try (ConfigurableApplicationContext initCtx = bootApp(hibernateDriftDb, Map.of())) {
            assertThat(initCtx.isRunning()).isTrue();
        }
        withJdbc(hibernateDriftDb, jdbc -> jdbc.execute("ALTER TABLE messages DROP COLUMN content"));

        assertThatThrownBy(() -> bootApp(hibernateDriftDb, Map.of()).close())
                .hasRootCauseInstanceOf(SchemaManagementException.class)
                .rootCause()
                .hasMessageContaining("missing column [content] in table [messages]");
    }

    @Test
    void blocksDestructiveDemoSeedInReleaseAndDefaultSqlSessionPreservingExistingData() throws Exception {
        String releaseDb = createFreshDatabase("heartlink_t04_release_guard");
        try (ConfigurableApplicationContext initCtx = bootApp(releaseDb, Map.of(
                "spring.profiles.active", "release"
        ))) {
            assertThat(initCtx.isRunning()).isTrue();
        }

        // Insert real release user data that must NEVER be truncated
        withJdbc(releaseDb, jdbc -> jdbc.update(
                "INSERT INTO users (email, password_hash, name, bio, created_at) VALUES ('real-user@heartlink.vn', 'hash', 'Real User', 'keep me', NOW())"
        ));

        // 1. Attempting to boot release profile with app.seed.demo-enabled=true fails fast
        assertThatThrownBy(() -> bootApp(releaseDb, Map.of(
                "spring.profiles.active", "release",
                "app.seed.demo-enabled", "true"
        )).close()).rootCause()
                .hasMessageContaining("[SEED-GUARD]");

        // 2. Executing seed/seed.sql directly without heartlink.allow_demo_seed=true aborts before TRUNCATE
        String seedSql = Files.readString(Path.of("seed/seed.sql"), StandardCharsets.UTF_8);
        try (Connection conn = DriverManager.getConnection(DB_HOST_PREFIX + releaseDb, DB_USER, DB_PASS);
             Statement stmt = conn.createStatement()) {
            assertThatThrownBy(() -> stmt.execute(seedSql))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("[SEED-GUARD]");
        }

        // 3. Even if allow_demo_seed=true is set, heartlink.env='release' STILL aborts before TRUNCATE
        try (Connection conn = DriverManager.getConnection(DB_HOST_PREFIX + releaseDb, DB_USER, DB_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET SESSION heartlink.env = 'release'");
            stmt.execute("SET SESSION heartlink.allow_demo_seed = 'true'");
            assertThatThrownBy(() -> stmt.execute(seedSql))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("[SEED-GUARD]");
        }

        // Verify the real release user was NEVER truncated or overwritten
        withJdbc(releaseDb, jdbc -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT email FROM users", String.class)).isEqualTo("real-user@heartlink.vn");
        });

        // 4. Explicit dev profile opt-in on a dedicated dev DB executes seed cleanly
        String devDb = createFreshDatabase("heartlink_t04_dev_seed");
        try (ConfigurableApplicationContext devCtx = bootApp(devDb, Map.of(
                "spring.profiles.active", "dev",
                "app.seed.demo-enabled", "true"
        ))) {
            assertThat(devCtx.isRunning()).isTrue();
        }
        withJdbc(devDb, jdbc -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(8L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM swipes", Long.class)).isEqualTo(17L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM matches", Long.class)).isEqualTo(4L);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(8L);
        });
    }

    private static ConfigurableApplicationContext bootApp(String dbName, Map<String, String> extraProps) {
        Map<String, String> argsMap = new LinkedHashMap<>();
        argsMap.put("spring.datasource.url", DB_HOST_PREFIX + dbName);
        argsMap.put("spring.datasource.username", DB_USER);
        argsMap.put("spring.datasource.password", DB_PASS);
        argsMap.put("DB_URL", DB_HOST_PREFIX + dbName);
        argsMap.put("DB_USERNAME", DB_USER);
        argsMap.put("DB_PASSWORD", DB_PASS);
        argsMap.put("JWT_SECRET", TEST_JWT_SECRET);
        argsMap.put("app.jwt.secret", TEST_JWT_SECRET);
        argsMap.putAll(extraProps);

        String[] cliArgs = argsMap.entrySet().stream()
                .map(e -> "--" + e.getKey() + "=" + e.getValue())
                .toArray(String[]::new);

        return new SpringApplicationBuilder(HeartlinkApplication.class)
                .web(WebApplicationType.NONE)
                .run(cliArgs);
    }

    private static String createFreshDatabase(String dbName) throws SQLException {
        try (Connection conn = DriverManager.getConnection(ADMIN_URL, DB_USER, DB_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP DATABASE IF EXISTS " + dbName);
            stmt.execute("CREATE DATABASE " + dbName);
            CREATED_DATABASES.add(dbName);
        }
        return dbName;
    }

    /**
     * Simulates an authentic pre-Flyway Phase 1 database generated by Hibernate ddl-auto=update
     * (including legacy Hibernate hash FK names and non-contiguous primary key IDs).
     */
    private static void seedLegacyPhase1SnapshotWithHibernateHashFkNames(String dbName) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_HOST_PREFIX + dbName, DB_USER, DB_PASS);
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE users (
                        id            BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
                        email         VARCHAR(255) NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        name          VARCHAR(100) NOT NULL,
                        bio           TEXT,
                        created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT users_pkey PRIMARY KEY (id),
                        CONSTRAINT uq_users_email UNIQUE (email)
                    );
                    CREATE TABLE refresh_tokens (
                        id         BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
                        user_id    BIGINT NOT NULL,
                        token_hash VARCHAR(64) NOT NULL,
                        expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        revoked_at TIMESTAMP(6) WITH TIME ZONE,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id),
                        CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash),
                        CONSTRAINT fk1lih5y2npsf8u5o3vhdb9y0os FOREIGN KEY (user_id) REFERENCES users (id)
                    );
                    CREATE TABLE swipes (
                        id         BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
                        swiper_id  BIGINT NOT NULL,
                        target_id  BIGINT NOT NULL,
                        action     VARCHAR(10) NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT swipes_pkey PRIMARY KEY (id),
                        CONSTRAINT uq_swipe_pair UNIQUE (swiper_id, target_id),
                        CONSTRAINT swipes_action_check CHECK (action IN ('LIKE', 'PASS')),
                        CONSTRAINT fkhnlpoujkrf69iv3la1uhvir2l FOREIGN KEY (swiper_id) REFERENCES users (id),
                        CONSTRAINT fknkspaofs7eek1k1pj065qwc98 FOREIGN KEY (target_id) REFERENCES users (id)
                    );
                    CREATE TABLE matches (
                        id           BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
                        user_a_id    BIGINT NOT NULL,
                        user_b_id    BIGINT NOT NULL,
                        status       VARCHAR(10) NOT NULL,
                        created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        cancelled_at TIMESTAMP(6) WITH TIME ZONE,
                        CONSTRAINT matches_pkey PRIMARY KEY (id),
                        CONSTRAINT uq_match_pair UNIQUE (user_a_id, user_b_id),
                        CONSTRAINT matches_status_check CHECK (status IN ('ACTIVE', 'CANCELLED')),
                        CONSTRAINT fkdbykfsi1adaf5jyru82gu0qin FOREIGN KEY (user_a_id) REFERENCES users (id),
                        CONSTRAINT fkeb45es49m11q3srmoycgonr2q FOREIGN KEY (user_b_id) REFERENCES users (id)
                    );
                    CREATE INDEX idx_matches_user_a_status_created ON matches (user_a_id, status, created_at);
                    CREATE INDEX idx_matches_user_b_status_created ON matches (user_b_id, status, created_at);
                    CREATE TABLE messages (
                        id         BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
                        match_id   BIGINT NOT NULL,
                        sender_id  BIGINT NOT NULL,
                        content    VARCHAR(2000) NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT messages_pkey PRIMARY KEY (id),
                        CONSTRAINT fkigt65pktmh2tch9d05wyr8dc3 FOREIGN KEY (match_id) REFERENCES matches (id),
                        CONSTRAINT fk4ui4nnwntodh6wjvck53dbk9m FOREIGN KEY (sender_id) REFERENCES users (id)
                    );
                    CREATE INDEX idx_messages_match_id ON messages (match_id, id);
                    CREATE TABLE notifications (
                        id         BIGINT GENERATED BY DEFAULT AS IDENTITY NOT NULL,
                        user_id    BIGINT NOT NULL,
                        match_id   BIGINT NOT NULL,
                        type       VARCHAR(30) NOT NULL,
                        created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
                        CONSTRAINT notifications_pkey PRIMARY KEY (id),
                        CONSTRAINT uq_notification_user_match_type UNIQUE (user_id, match_id, type),
                        CONSTRAINT notifications_type_check CHECK (type IN ('MATCH_CREATED')),
                        CONSTRAINT fk9y21adhxn0ayjhfocscqox7bh FOREIGN KEY (user_id) REFERENCES users (id),
                        CONSTRAINT fkh956mebubxqbpnu49f5lykbmp FOREIGN KEY (match_id) REFERENCES matches (id)
                    );
                    CREATE INDEX idx_notifications_user_id ON notifications (user_id, id);

                    INSERT INTO users (id, email, password_hash, name, bio, created_at) OVERRIDING SYSTEM VALUE VALUES
                        (10, 'u10@example.com', 'hash10', 'User 10', 'Bio 10', '2026-01-01T00:00:00Z'),
                        (20, 'u20@example.com', 'hash20', 'User 20', NULL,     '2026-01-02T00:00:00Z'),
                        (35, 'u35@example.com', 'hash35', 'User 35', 'Bio 35', '2026-01-03T00:00:00Z'),
                        (50, 'u50@example.com', 'hash50', 'User 50', 'Bio 50', '2026-01-04T00:00:00Z');
                    SELECT setval(pg_get_serial_sequence('users', 'id'), 50, true);

                    INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked_at, created_at) OVERRIDING SYSTEM VALUE VALUES
                        (101, 10, repeat('a', 64), '2027-01-01T00:00:00Z', NULL, '2026-01-05T00:00:00Z'),
                        (102, 20, repeat('b', 64), '2027-01-01T00:00:00Z', '2026-02-01T00:00:00Z', '2026-01-05T00:00:00Z');
                    SELECT setval(pg_get_serial_sequence('refresh_tokens', 'id'), 102, true);

                    INSERT INTO swipes (id, swiper_id, target_id, action, created_at) OVERRIDING SYSTEM VALUE VALUES
                        (201, 10, 20, 'LIKE', '2026-01-06T00:00:00Z'),
                        (202, 20, 10, 'LIKE', '2026-01-06T00:01:00Z'),
                        (203, 10, 35, 'LIKE', '2026-01-06T00:02:00Z'),
                        (204, 35, 10, 'LIKE', '2026-01-06T00:03:00Z');
                    SELECT setval(pg_get_serial_sequence('swipes', 'id'), 204, true);

                    INSERT INTO matches (id, user_a_id, user_b_id, status, created_at, cancelled_at) OVERRIDING SYSTEM VALUE VALUES
                        (301, 10, 20, 'ACTIVE',    '2026-01-06T00:01:00Z', NULL),
                        (302, 10, 35, 'CANCELLED', '2026-01-06T00:03:00Z', '2026-01-07T00:00:00Z');
                    SELECT setval(pg_get_serial_sequence('matches', 'id'), 302, true);

                    INSERT INTO messages (id, match_id, sender_id, content, created_at) OVERRIDING SYSTEM VALUE VALUES
                        (401, 301, 10, 'Xin chào!', '2026-01-06T00:10:00Z'),
                        (402, 301, 20, 'Chào bạn!', '2026-01-06T00:11:00Z'),
                        (403, 302, 10, 'Tin cũ trước khi huỷ', '2026-01-06T00:12:00Z');
                    SELECT setval(pg_get_serial_sequence('messages', 'id'), 403, true);

                    INSERT INTO notifications (id, user_id, match_id, type, created_at) OVERRIDING SYSTEM VALUE VALUES
                        (501, 10, 301, 'MATCH_CREATED', '2026-01-06T00:01:00Z'),
                        (502, 20, 301, 'MATCH_CREATED', '2026-01-06T00:01:00Z'),
                        (503, 10, 302, 'MATCH_CREATED', '2026-01-06T00:03:00Z'),
                        (504, 35, 302, 'MATCH_CREATED', '2026-01-06T00:03:00Z');
                    SELECT setval(pg_get_serial_sequence('notifications', 'id'), 504, true);
                    """);
        }
    }

    private record SnapshotState(
            Map<String, Long> rowCounts,
            Map<String, List<Long>> idsByTable,
            Set<String> foreignKeys,
            Set<String> indexes
    ) {
    }

    private static SnapshotState captureSnapshotState(String dbName) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, List<Long>> ids = new LinkedHashMap<>();
        Set<String> fks = new TreeSet<>();
        Set<String> indexes = new TreeSet<>();

        withJdbc(dbName, jdbc -> {
            for (String table : CORE_TABLES) {
                counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
                ids.put(table, jdbc.queryForList("SELECT id FROM " + table + " ORDER BY id", Long.class));
            }
            fks.addAll(jdbc.queryForList("""
                    SELECT src_cls.relname || '(' || src_att.attname || ')->' || dst_cls.relname || '(' || dst_att.attname || ')'
                      FROM pg_constraint con
                      JOIN pg_class src_cls ON src_cls.oid = con.conrelid
                      JOIN pg_namespace nsp ON nsp.oid = src_cls.relnamespace
                      JOIN pg_class dst_cls ON dst_cls.oid = con.confrelid
                      JOIN pg_attribute src_att ON src_att.attrelid = src_cls.oid AND src_att.attnum = con.conkey[1]
                      JOIN pg_attribute dst_att ON dst_att.attrelid = dst_cls.oid AND dst_att.attnum = con.confkey[1]
                     WHERE nsp.nspname = 'public' AND con.contype = 'f'
                    """, String.class));
            indexes.addAll(jdbc.queryForList("""
                    SELECT tablename || ':' || indexname || '=' || indexdef
                      FROM pg_indexes
                     WHERE schemaname = 'public'
                       AND tablename <> 'flyway_schema_history'
                    """, String.class));
        });
        return new SnapshotState(counts, ids, fks, indexes);
    }

    private interface JdbcCallback {
        void accept(JdbcTemplate jdbc);
    }

    private static void withJdbc(String dbName, JdbcCallback callback) throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_HOST_PREFIX + dbName, DB_USER, DB_PASS)) {
            SingleConnectionDataSource ds = new SingleConnectionDataSource(conn, true);
            callback.accept(new JdbcTemplate(ds));
        }
    }
}


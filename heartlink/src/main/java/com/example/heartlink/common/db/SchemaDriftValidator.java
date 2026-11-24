package com.example.heartlink.common.db;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only PostgreSQL metadata verifier that detects schema drift across tables,
 * column types/lengths/nullability, PKs, unique constraints, foreign keys, check constraints,
 * and secondary indexes without mutating database state.
 */
public class SchemaDriftValidator {

    public record ColumnSpec(String table, String column, String dataType, Integer maxLength, boolean nullable) {
    }

    public record ForeignKeySpec(String table, String column, String refTable, String refColumn) {
    }

    private static final List<String> EXPECTED_TABLES = List.of(
            "users", "refresh_tokens", "swipes", "matches", "messages", "notifications"
    );

    private static final List<ColumnSpec> EXPECTED_COLUMNS = List.of(
            // users
            new ColumnSpec("users", "id", "bigint", null, false),
            new ColumnSpec("users", "email", "character varying", 255, false),
            new ColumnSpec("users", "password_hash", "character varying", 255, false),
            new ColumnSpec("users", "name", "character varying", 100, false),
            new ColumnSpec("users", "bio", "text", null, true),
            new ColumnSpec("users", "created_at", "timestamp with time zone", null, false),
            // refresh_tokens
            new ColumnSpec("refresh_tokens", "id", "bigint", null, false),
            new ColumnSpec("refresh_tokens", "user_id", "bigint", null, false),
            new ColumnSpec("refresh_tokens", "token_hash", "character varying", 64, false),
            new ColumnSpec("refresh_tokens", "expires_at", "timestamp with time zone", null, false),
            new ColumnSpec("refresh_tokens", "revoked_at", "timestamp with time zone", null, true),
            new ColumnSpec("refresh_tokens", "created_at", "timestamp with time zone", null, false),
            // swipes
            new ColumnSpec("swipes", "id", "bigint", null, false),
            new ColumnSpec("swipes", "swiper_id", "bigint", null, false),
            new ColumnSpec("swipes", "target_id", "bigint", null, false),
            new ColumnSpec("swipes", "action", "character varying", 10, false),
            new ColumnSpec("swipes", "created_at", "timestamp with time zone", null, false),
            // matches
            new ColumnSpec("matches", "id", "bigint", null, false),
            new ColumnSpec("matches", "user_a_id", "bigint", null, false),
            new ColumnSpec("matches", "user_b_id", "bigint", null, false),
            new ColumnSpec("matches", "status", "character varying", 10, false),
            new ColumnSpec("matches", "created_at", "timestamp with time zone", null, false),
            new ColumnSpec("matches", "cancelled_at", "timestamp with time zone", null, true),
            // messages
            new ColumnSpec("messages", "id", "bigint", null, false),
            new ColumnSpec("messages", "match_id", "bigint", null, false),
            new ColumnSpec("messages", "sender_id", "bigint", null, false),
            new ColumnSpec("messages", "content", "character varying", 2000, false),
            new ColumnSpec("messages", "created_at", "timestamp with time zone", null, false),
            // notifications
            new ColumnSpec("notifications", "id", "bigint", null, false),
            new ColumnSpec("notifications", "user_id", "bigint", null, false),
            new ColumnSpec("notifications", "match_id", "bigint", null, false),
            new ColumnSpec("notifications", "type", "character varying", 30, false),
            new ColumnSpec("notifications", "created_at", "timestamp with time zone", null, false)
    );

    private static final Map<String, String> EXPECTED_PRIMARY_KEYS = Map.of(
            "users", "id",
            "refresh_tokens", "id",
            "swipes", "id",
            "matches", "id",
            "messages", "id",
            "notifications", "id"
    );

    private static final Set<String> EXPECTED_UNIQUES = Set.of(
            "users(email)",
            "refresh_tokens(token_hash)",
            "swipes(swiper_id,target_id)",
            "matches(user_a_id,user_b_id)",
            "notifications(user_id,match_id,type)"
    );

    private static final Set<ForeignKeySpec> EXPECTED_FOREIGN_KEYS = Set.of(
            new ForeignKeySpec("refresh_tokens", "user_id", "users", "id"),
            new ForeignKeySpec("swipes", "swiper_id", "users", "id"),
            new ForeignKeySpec("swipes", "target_id", "users", "id"),
            new ForeignKeySpec("matches", "user_a_id", "users", "id"),
            new ForeignKeySpec("matches", "user_b_id", "users", "id"),
            new ForeignKeySpec("messages", "match_id", "matches", "id"),
            new ForeignKeySpec("messages", "sender_id", "users", "id"),
            new ForeignKeySpec("notifications", "user_id", "users", "id"),
            new ForeignKeySpec("notifications", "match_id", "matches", "id")
    );

    private static final Set<String> EXPECTED_INDEXES = Set.of(
            "matches.idx_matches_user_a_status_created",
            "matches.idx_matches_user_b_status_created",
            "messages.idx_messages_match_id",
            "notifications.idx_notifications_user_id"
    );

    private static final Set<String> EXPECTED_V2_CHECKS = Set.of(
            "swipes.chk_swipes_no_self",
            "matches.chk_matches_canonical_pair"
    );

    /**
     * Audits a pre-Flyway Phase 1 database snapshot before allowing an explicit baseline at V1.
     */
    public void verifyPhase1BaselineContract(DataSource dataSource) {
        List<String> violations = collectCoreViolations(dataSource, false);
        if (!violations.isEmpty()) {
            throw new SchemaDriftException("Pre-baseline Phase 1 snapshot audit", violations);
        }
    }

    /**
     * Audits the post-migration schema (V1 + V2) on application startup.
     */
    public void verifyPostMigrationContract(DataSource dataSource) {
        List<String> violations = collectCoreViolations(dataSource, true);
        if (!violations.isEmpty()) {
            throw new SchemaDriftException("Post-migration startup validation", violations);
        }
    }

    public boolean hasFlywayHistoryTable(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1 FROM information_schema.tables
                          WHERE table_schema = 'public'
                            AND table_name = 'flyway_schema_history'
                     )
                     """);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to inspect flyway_schema_history existence", e);
        }
    }

    public boolean hasNonEmptyPublicSchema(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1 FROM information_schema.tables
                          WHERE table_schema = 'public'
                            AND table_type = 'BASE TABLE'
                            AND table_name <> 'flyway_schema_history'
                     )
                     """);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to inspect public schema tables", e);
        }
    }

    private List<String> collectCoreViolations(DataSource dataSource, boolean requireV2AndHistory) {
        List<String> violations = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            Set<String> tables = loadPublicTables(conn);
            for (String expectedTable : EXPECTED_TABLES) {
                if (!tables.contains(expectedTable)) {
                    violations.add("Missing table: " + expectedTable);
                }
            }

            Map<String, ColumnSpec> actualColumns = loadColumns(conn);
            for (ColumnSpec expected : EXPECTED_COLUMNS) {
                if (!tables.contains(expected.table())) {
                    continue;
                }
                String key = expected.table() + "." + expected.column();
                ColumnSpec actual = actualColumns.get(key);
                if (actual == null) {
                    violations.add("Missing column: " + key);
                    continue;
                }
                if (!expected.dataType().equalsIgnoreCase(actual.dataType())) {
                    violations.add("Column type mismatch on " + key + ": expected '"
                            + expected.dataType() + "', actual '" + actual.dataType() + "'");
                }
                if (expected.maxLength() != null && !Objects.equals(expected.maxLength(), actual.maxLength())) {
                    violations.add("Column length mismatch on " + key + ": expected "
                            + expected.maxLength() + ", actual " + actual.maxLength());
                }
                if (expected.nullable() != actual.nullable()) {
                    violations.add("Nullability mismatch on " + key + ": expected nullable="
                            + expected.nullable() + ", actual nullable=" + actual.nullable());
                }
            }

            Map<String, String> actualPks = loadSingleColumnConstraints(conn, "p");
            for (var entry : EXPECTED_PRIMARY_KEYS.entrySet()) {
                if (!tables.contains(entry.getKey())) {
                    continue;
                }
                String actualPk = actualPks.get(entry.getKey());
                if (!entry.getValue().equals(actualPk)) {
                    violations.add("Primary key mismatch on " + entry.getKey()
                            + ": expected (" + entry.getValue() + "), actual (" + actualPk + ")");
                }
            }

            Set<String> actualUniques = loadUniqueTuples(conn);
            for (String expectedUq : EXPECTED_UNIQUES) {
                String tbl = expectedUq.substring(0, expectedUq.indexOf('('));
                if (tables.contains(tbl) && !actualUniques.contains(expectedUq)) {
                    violations.add("Missing unique constraint: " + expectedUq);
                }
            }

            Set<ForeignKeySpec> actualFks = loadForeignKeys(conn);
            for (ForeignKeySpec expectedFk : EXPECTED_FOREIGN_KEYS) {
                if (tables.contains(expectedFk.table()) && !actualFks.contains(expectedFk)) {
                    violations.add("Missing foreign key: " + expectedFk.table() + "(" + expectedFk.column()
                            + ") -> " + expectedFk.refTable() + "(" + expectedFk.refColumn() + ")");
                }
            }

            Set<String> actualIndexes = loadIndexes(conn, violations);
            for (String expectedIdx : EXPECTED_INDEXES) {
                String tbl = expectedIdx.substring(0, expectedIdx.indexOf('.'));
                if (tables.contains(tbl) && !actualIndexes.contains(expectedIdx)) {
                    violations.add("Missing secondary index: " + expectedIdx);
                }
            }

            if (requireV2AndHistory) {
                Set<String> actualChecks = loadNamedCheckConstraints(conn);
                for (String expectedCheck : EXPECTED_V2_CHECKS) {
                    String tbl = expectedCheck.substring(0, expectedCheck.indexOf('.'));
                    if (tables.contains(tbl) && !actualChecks.contains(expectedCheck)) {
                        violations.add("Missing V2 check constraint: " + expectedCheck);
                    }
                }
                checkFlywayHistory(conn, tables, violations);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to execute PostgreSQL schema drift check", e);
        }
        return violations;
    }

    private Set<String> loadPublicTables(Connection conn) throws SQLException {
        Set<String> tables = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'public'
                   AND table_type = 'BASE TABLE'
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private Map<String, ColumnSpec> loadColumns(Connection conn) throws SQLException {
        Map<String, ColumnSpec> cols = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT table_name, column_name, data_type, character_maximum_length, is_nullable
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                String col = rs.getString("column_name");
                String type = rs.getString("data_type");
                int rawLen = rs.getInt("character_maximum_length");
                Integer maxLen = rs.wasNull() ? null : rawLen;
                boolean nullable = "YES".equalsIgnoreCase(rs.getString("is_nullable"));
                cols.put(table + "." + col, new ColumnSpec(table, col, type, maxLen, nullable));
            }
        }
        return cols;
    }

    private Map<String, String> loadSingleColumnConstraints(Connection conn, String contype) throws SQLException {
        Map<String, String> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT cls.relname AS table_name,
                       string_agg(att.attname, ',' ORDER BY ord.n) AS cols
                  FROM pg_constraint con
                  JOIN pg_class cls ON cls.oid = con.conrelid
                  JOIN pg_namespace nsp ON nsp.oid = cls.relnamespace
                  JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS ord(attnum, n) ON true
                  JOIN pg_attribute att ON att.attrelid = cls.oid AND att.attnum = ord.attnum
                 WHERE nsp.nspname = 'public'
                   AND con.contype = ?
                 GROUP BY cls.relname, con.oid
                """)) {
            ps.setString(1, contype);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.put(rs.getString("table_name"), rs.getString("cols"));
                }
            }
        }
        return map;
    }

    private Set<String> loadUniqueTuples(Connection conn) throws SQLException {
        Set<String> uniques = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT cls.relname AS table_name,
                       string_agg(att.attname, ',' ORDER BY ord.n) AS cols
                  FROM pg_constraint con
                  JOIN pg_class cls ON cls.oid = con.conrelid
                  JOIN pg_namespace nsp ON nsp.oid = cls.relnamespace
                  JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS ord(attnum, n) ON true
                  JOIN pg_attribute att ON att.attrelid = cls.oid AND att.attnum = ord.attnum
                 WHERE nsp.nspname = 'public'
                   AND con.contype = 'u'
                 GROUP BY cls.relname, con.oid
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                uniques.add(rs.getString("table_name") + "(" + rs.getString("cols") + ")");
            }
        }
        return uniques;
    }

    private Set<ForeignKeySpec> loadForeignKeys(Connection conn) throws SQLException {
        Set<ForeignKeySpec> fks = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT src_cls.relname AS table_name,
                       src_att.attname AS column_name,
                        dst_cls.relname AS ref_table,
                       dst_att.attname AS ref_column
                  FROM pg_constraint con
                  JOIN pg_class src_cls ON src_cls.oid = con.conrelid
                  JOIN pg_namespace nsp ON nsp.oid = src_cls.relnamespace
                  JOIN pg_class dst_cls ON dst_cls.oid = con.confrelid
                  JOIN pg_attribute src_att ON src_att.attrelid = src_cls.oid AND src_att.attnum = con.conkey[1]
                  JOIN pg_attribute dst_att ON dst_att.attrelid = dst_cls.oid AND dst_att.attnum = con.confkey[1]
                 WHERE nsp.nspname = 'public'
                   AND con.contype = 'f'
                   AND array_length(con.conkey, 1) = 1
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                fks.add(new ForeignKeySpec(
                        rs.getString("table_name"),
                        rs.getString("column_name"),
                        rs.getString("ref_table"),
                        rs.getString("ref_column")));
            }
        }
        return fks;
    }

    private Set<String> loadIndexes(Connection conn, List<String> violations) throws SQLException {
        Set<String> indexes = new HashSet<>();
        Map<String, String> normalizedIndexDefs = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT tablename, indexname,
                       regexp_replace(indexdef, '^CREATE (UNIQUE )?INDEX [^ ]+ ', '') AS norm_def
                  FROM pg_indexes
                 WHERE schemaname = 'public'
                   AND tablename <> 'flyway_schema_history'
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString("tablename");
                String idx = rs.getString("indexname");
                String normDef = rs.getString("norm_def");
                indexes.add(table + "." + idx);
                String prev = normalizedIndexDefs.putIfAbsent(table + ":" + normDef, idx);
                if (prev != null && EXPECTED_TABLES.contains(table)) {
                    violations.add("Duplicate index definition on " + table + ": '" + prev + "' and '" + idx + "'");
                }
            }
        }
        return indexes;
    }

    private Set<String> loadNamedCheckConstraints(Connection conn) throws SQLException {
        Set<String> checks = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT cls.relname AS table_name, con.conname AS constraint_name
                  FROM pg_constraint con
                  JOIN pg_class cls ON cls.oid = con.conrelid
                  JOIN pg_namespace nsp ON nsp.oid = cls.relnamespace
                 WHERE nsp.nspname = 'public'
                   AND con.contype = 'c'
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                checks.add(rs.getString("table_name") + "." + rs.getString("constraint_name"));
            }
        }
        return checks;
    }

    private void checkFlywayHistory(Connection conn, Set<String> tables, List<String> violations) throws SQLException {
        if (!tables.contains("flyway_schema_history")) {
            violations.add("Missing table: flyway_schema_history");
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT count(*) FILTER (WHERE NOT success) AS failed_count,
                       max(CASE WHEN version ~ '^[0-9]+$' THEN version::int ELSE 0 END) AS max_version
                  FROM flyway_schema_history
                """);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                if (rs.getInt("failed_count") > 0) {
                    violations.add("flyway_schema_history contains failed migration entries");
                }
                if (rs.getInt("max_version") < 2) {
                    violations.add("Expected Flyway schema version >= 2, actual: " + rs.getInt("max_version"));
                }
            }
        }
    }
}


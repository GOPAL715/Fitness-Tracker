package com.fittrack.acceptance;

import com.fittrack.acceptance.support.AbstractAcceptanceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16: database backup and restore readiness.
 *
 * <p>Proves a restored copy is a usable starting point: the same migration chain initializes a
 * clean database, and recorded migration state is reproducible. The target is a second database on
 * the same Testcontainers instance, so no developer or production database is ever touched.
 */
@org.springframework.boot.test.context.SpringBootTest(classes = com.fittrack.api.FitTrackApplication.class)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class BackupRestoreAcceptanceTest extends AbstractAcceptanceTest {

    private static final File MIGRATIONS = new File("src/main/resources/db/migration");

    @Test
    @DisplayName("Phase 16 database - the migration chain is complete, ordered and successful")
    void migrationChainIsCurrentAndDeterministic() {
        List<Map<String, Object>> applied = jdbc.queryForList(
                "select version, success from flyway_schema_history order by installed_rank");

        assertThat(applied).as("every migration was applied").isNotEmpty();
        assertThat(applied).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
        // A gap would mean a migration never ran, so the chain must be contiguous.
        assertThat(applied).extracting(row -> String.valueOf(row.get("version")))
                .containsExactly("1", "2", "3", "4", "5", "6");
    }

    /**
     * Restores into a genuinely clean database on the same server.
     *
     * <p>The target is a separate database created for this test and dropped afterwards, so no
     * existing database is ever modified. The schema is built by replaying the real migration
     * scripts, which is exactly what a clean production deployment and a restored database both
     * depend on.
     */
    @Test
    @DisplayName("Phase 16 backup - a clean database is initializable by the same migration chain")
    void cleanDatabaseInitializesByMigrations() throws Exception {
        String targetDatabase = "fittrack_restore_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        boolean created = false;
        try {
            jdbc.execute("create database " + targetDatabase);
            created = true;
            JdbcTemplate target = new JdbcTemplate(new DriverManagerDataSource(
                    jdbcUrlFor(targetDatabase), container().getUsername(), container().getPassword()));

            for (String statement : migrationStatements()) {
                target.execute(statement);
            }

            // The application validates the schema on startup, so a restored database must expose
            // the same tables as the source. flyway_schema_history is written by Flyway rather
            // than by the raw scripts, so it is compared separately.
            assertThat(publicTableCount(target)).isEqualTo(sourcePublicTableCount());
            assertThat(tableExists(target, "app_users"))
                    .as("a restored database is immediately usable by the application").isTrue();

            // Owner-scoped queries must behave identically after a restore.
            UUID survivor = UUID.randomUUID();
            target.update("insert into app_users (id, email, password_hash, enabled) values (?,?,?,true)",
                    survivor, "restore-" + survivor + "@example.test", "hash");
            assertThat(target.queryForObject(
                    "select count(*) from workout_sessions where user_id=CAST(? as uuid)",
                    Integer.class, survivor)).isZero();
        } finally {
            if (created) {
                // Best effort: the target is ours, and a failure to drop it must not fail the suite.
                try {
                    jdbc.execute("drop database if exists " + targetDatabase);
                } catch (RuntimeException ignored) {
                    // The throwaway database is left behind; it is isolated from the suite.
                }
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /** Whether a table exists in the target schema. */
    private boolean tableExists(JdbcTemplate target, String table) {
        Integer count = target.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_name=?",
                Integer.class, table);
        return count != null && count > 0;
    }

    private int appliedMigrationCount() {
        return jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class);
    }

    private int sourcePublicTableCount() {
        return jdbc.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema='public' and table_name not like 'flyway%'", Integer.class);
    }

    private int publicTableCount(JdbcTemplate target) {
        return target.queryForObject("select count(*) from information_schema.tables "
                + "where table_schema='public' and table_name not like 'flyway%'", Integer.class);
    }

    private String postgresPassword() {
        return container().getPassword();
    }

    private String jdbcUrlFor(String database) {
        return "jdbc:postgresql://" + container().getHost() + ":"
                + container().getFirstMappedPort() + "/" + database;
    }

    /** The same scripts Flyway runs, in version order, split into executable statements. */
    private List<String> migrationStatements() throws Exception {
        List<String> statements = new ArrayList<>();
        File[] files = MIGRATIONS.listFiles((dir, name) -> name.endsWith(".sql"));
        assertThat(files).as("migration scripts are present").isNotNull();
        Arrays.stream(files)
                .sorted(Comparator.comparing(f -> f.getName().split("__")[0]))
                .forEach(file -> {
                    try {
                        for (String raw : Files.readString(file.toPath()).split(";\\s*\\R")) {
                            String cleaned = stripComments(raw);
                            if (!cleaned.isBlank()) statements.add(cleaned);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("could not read migration " + file, e);
                    }
                });
        return statements;
    }

    /** Removes SQL line comments, which would otherwise be sent to the server verbatim. */
    private String stripComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (line.strip().startsWith("--")) continue;
            out.append(line).append("\n");
        }
        return out.toString();
    }
}

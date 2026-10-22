package com.eu.habbo.networking.gameserver.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.eu.habbo.database.TestDatabase;
import com.eu.habbo.database.migration.MigrationRunner;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the production reset transaction against the real migrated MariaDB schema. */
class PasswordResetTransactionIT {
    private static final int USER_ID = 910030;
    private static final String TOKEN = "A".repeat(43);
    private static final String OLD_PASSWORD = "previous-test-password";
    private static final String NEW_PASSWORD = "replacement-test-password";
    private static HikariDataSource dataSource;

    @BeforeAll
    static void migrate() {
        if (!TestDatabase.dockerAvailable()) {
            if ("true".equalsIgnoreCase(System.getenv("CI"))) {
                fail("Docker is required for password recovery integration tests in CI");
            }
            assumeTrue(false, "Docker is not available");
        }
        dataSource = TestDatabase.freshDatabase("password_reset");
        MigrationRunner.migrate(dataSource);
    }

    @AfterAll
    static void close() {
        if (dataSource != null) dataSource.close();
    }

    @BeforeEach
    void seed() throws Exception {
        execute(
                "DROP TRIGGER IF EXISTS fail_verification_cleanup",
                "DELETE FROM password_resets WHERE user_id = 910030",
                "DELETE FROM email_verification_tokens WHERE user_id = 910030",
                "DELETE FROM users_remember_families WHERE user_id = 910030",
                "DELETE FROM users WHERE id = 910030",
                "INSERT INTO users (id, username, password, ip_register, ip_current, credits, mail, email_verified_at, auth_ticket, auth_ticket_expires_at, access_token_version) "
                        + "VALUES (910030, 'reset_transaction_it', 'pending', '127.0.0.1', '127.0.0.1', 1234, 'recovery@example.invalid', NOW(), 'old-sso-ticket', DATE_ADD(NOW(), INTERVAL 1 HOUR), 7)",
                "INSERT INTO users_remember_families (family_id, user_id, created_at, expires_at) VALUES ('reset-test-family', 910030, 1, 2147483647)",
                "INSERT INTO email_verification_tokens (user_id, email, token_hash, expires_at) VALUES (910030, 'pending@example.invalid', REPEAT('b', 64), DATE_ADD(NOW(), INTERVAL 30 MINUTE))");
        try (Connection conn = dataSource.getConnection();
                var user = conn.prepareStatement("UPDATE users SET password = ? WHERE id = ?");
                var token = conn.prepareStatement(
                        "INSERT INTO password_resets (user_id, token, expires_at) VALUES (?, ?, DATE_ADD(NOW(), INTERVAL 20 MINUTE))")) {
            user.setString(1, PasswordHasher.hash(OLD_PASSWORD, 4));
            user.setInt(2, USER_ID);
            user.executeUpdate();
            token.setInt(1, USER_ID);
            token.setString(2, PasswordResetEndpoints.hashToken(TOKEN));
            token.executeUpdate();
        }
    }

    @Test
    void resetRevokesCredentialsAndPendingEmailChangeWithoutChangingProfile() throws Exception {
        assertEquals(USER_ID, reset(TOKEN));
        assertTrue(PasswordHasher.verify(NEW_PASSWORD, scalar("SELECT password FROM users WHERE id = 910030")));
        assertFalse(PasswordHasher.verify(OLD_PASSWORD, scalar("SELECT password FROM users WHERE id = 910030")));
        assertEquals("8", scalar("SELECT access_token_version FROM users WHERE id = 910030"));
        assertEquals("", scalar("SELECT auth_ticket FROM users WHERE id = 910030"));
        assertEquals("1", scalar("SELECT auth_ticket_expires_at IS NULL FROM users WHERE id = 910030"));
        assertEquals("1", scalar("SELECT revoked FROM users_remember_families WHERE user_id = 910030"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM password_resets WHERE user_id = 910030"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = 910030"));
        assertEquals("1234", scalar("SELECT credits FROM users WHERE id = 910030"));
        assertEquals("recovery@example.invalid", scalar("SELECT mail FROM users WHERE id = 910030"));
    }

    @Test
    void spentAndUnknownTokensCannotChangeCredentials() throws Exception {
        assertEquals(0, reset("C".repeat(43)));
        assertEquals(USER_ID, reset(TOKEN));
        assertEquals(0, reset(TOKEN));
        assertEquals("8", scalar("SELECT access_token_version FROM users WHERE id = 910030"));
    }

    @Test
    void expiredTokenLeavesCredentialsAndPendingEmailUntouched() throws Exception {
        execute("UPDATE password_resets SET expires_at = DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE user_id = 910030");
        assertEquals(0, reset(TOKEN));
        assertUnchanged();
    }

    @Test
    void concurrentSubmissionsConsumeTheTokenExactlyOnce() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> attempt = () -> {
                try (Connection conn = dataSource.getConnection()) {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
                    return PasswordResetEndpoints.consumeToken(conn, TOKEN, NEW_PASSWORD);
                }
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
            } finally {
                start.countDown();
            }
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertEquals(1L, results.stream().filter(id -> id == USER_ID).count());
            assertEquals(1L, results.stream().filter(id -> id == 0).count());
        }
        assertEquals("8", scalar("SELECT access_token_version FROM users WHERE id = 910030"));
    }

    @Test
    void failureAfterCredentialUpdatesRollsBackEverythingAndAllowsRetry() throws Exception {
        execute("CREATE TRIGGER fail_verification_cleanup BEFORE DELETE ON email_verification_tokens "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test rollback'");
        try {
            assertThrows(Exception.class, () -> reset(TOKEN));
            assertUnchanged();
            assertEquals("1", scalar("SELECT COUNT(*) FROM password_resets WHERE user_id = 910030"));
        } finally {
            execute("DROP TRIGGER fail_verification_cleanup");
        }
        assertEquals(USER_ID, reset(TOKEN));
    }

    private int reset(String token) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            try {
                return PasswordResetEndpoints.consumeToken(conn, token, NEW_PASSWORD);
            } finally {
                assertTrue(conn.getAutoCommit(), "The pooled connection must return to auto-commit mode");
            }
        }
    }

    private void assertUnchanged() throws Exception {
        assertTrue(PasswordHasher.verify(OLD_PASSWORD, scalar("SELECT password FROM users WHERE id = 910030")));
        assertEquals("7", scalar("SELECT access_token_version FROM users WHERE id = 910030"));
        assertEquals("old-sso-ticket", scalar("SELECT auth_ticket FROM users WHERE id = 910030"));
        assertEquals("0", scalar("SELECT revoked FROM users_remember_families WHERE user_id = 910030"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = 910030"));
    }

    private static void execute(String... statements) throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement()) {
            for (String sql : statements) stmt.execute(sql);
        }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }
}

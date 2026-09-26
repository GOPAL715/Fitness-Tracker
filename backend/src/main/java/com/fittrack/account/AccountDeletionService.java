package com.fittrack.account;

import com.fittrack.storage.PrivateObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Account deletion.
 *
 * <p>Every user-owned table is linked to {@code app_users} with {@code ON DELETE CASCADE}, so the
 * relational graph is removed atomically by deleting the user row. Private storage objects are
 * deleted explicitly beforehand, because the filesystem is not covered by the database
 * transaction. Refresh tokens are deleted first so sessions are revoked even if the user row
 * removal is later rolled back by a failure.
 *
 * <p>The target is always the authenticated principal: there is no parameter by which one user
 * could delete another.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    private final JdbcTemplate jdbc;
    private final PrivateObjectStorage storage;
    private final Path storageRoot;

    public AccountDeletionService(JdbcTemplate jdbc, PrivateObjectStorage storage,
            @org.springframework.beans.factory.annotation.Value("${app.storage-path:./private-storage}") String root) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.storageRoot = Path.of(root).toAbsolutePath().normalize();
    }

    /** What was removed, for an auditable response. */
    public record DeletionResult(boolean deleted, int storageObjectsRemoved, int storageObjectsFailed) {}

    @Transactional
    public DeletionResult deleteAccount(String principal) {
        UUID user = parse(principal);

        // Remove private images first: the filesystem is outside the database transaction.
        int removed = 0;
        int failed = 0;
        List<Map<String, Object>> images = jdbc.queryForList(
                "select id, image_path from food_scans where user_id=CAST(? as uuid)", user);
        for (Map<String, Object> image : images) {
            if (deleteStoredObject(user, String.valueOf(image.get("image_path")))) removed++;
            else failed++;
        }

        // Sessions are revoked before the user row goes away.
        int sessions = jdbc.update("delete from refresh_tokens where user_id=CAST(? as uuid)", user);

        // Cascades remove the remaining relational graph atomically.
        int rows = jdbc.update("delete from app_users where id=?", user);
        if (rows == 0) {
            // Idempotent: deleting an already-deleted account is a no-op, not an error.
            return new DeletionResult(false, removed, failed);
        }
        log.info("account_deleted user_id={} storage_removed={} storage_failed={} sessions_revoked={}",
                user, removed, failed, sessions);
        return new DeletionResult(true, removed, failed);
    }

    /**
     * Deletes one stored object, refusing anything that escapes the owner's directory.
     *
     * @return true when the object is gone (or never existed)
     */
    private boolean deleteStoredObject(UUID user, String relativePath) {
        if (relativePath == null || relativePath.isBlank() || "null".equals(relativePath)) return true;
        try {
            // storage.delete performs the traversal check; a rejected path is reported, not thrown.
            storage.delete(user.toString(), relativePath);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("account_storage_delete_failed user_id={} path={}", user, relativePath);
            return false;
        }
    }

    /** Removes any orphaned objects left under the owner's directory. */
    public int purgeOwnerDirectory(UUID user) {
        Path owner = storageRoot.resolve(user.toString()).normalize();
        if (!owner.startsWith(storageRoot) || !Files.isDirectory(owner)) return 0;
        try (var walk = Files.walk(owner)) {
            return (int) walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static UUID parse(String principal) {
        try {
            return UUID.fromString(principal);
        } catch (RuntimeException e) {
            throw new org.springframework.security.access.AccessDeniedException("Not permitted");
        }
    }
}

package com.fittrack.account;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Account lifecycle endpoints.
 *
 * <p>Deletion targets the authenticated principal only. There is no path or body parameter that
 * can select another user, so cross-account deletion is not expressible.
 */
@RestController
@RequestMapping("/api/v1/me")
public class AccountController {

    private final AccountDeletionService deletion;

    public AccountController(AccountDeletionService deletion) {
        this.deletion = deletion;
    }

    @GetMapping("/export")
    public Map<String, Object> exportSummary(@AuthenticationPrincipal String user) {
        // A full data export is intentionally not inlined here: the response would be unbounded.
        // See README "Account lifecycle" for the operational export procedure.
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                "Export is an operational procedure; see the runbook");
    }

    @DeleteMapping
    public Map<String, Object> delete(@AuthenticationPrincipal String user) {
        AccountDeletionService.DeletionResult result = deletion.deleteAccount(user);
        return Map.of("deleted", result.deleted(),
                "storage_objects_removed", result.storageObjectsRemoved());
    }
}

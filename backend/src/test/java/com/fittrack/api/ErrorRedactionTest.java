package com.fittrack.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16 follow-up: the unhandled-exception log must never carry credential material.
 *
 * <p>A constraint violation reports the offending column and value, so a refresh-token hash can
 * appear verbatim in the exception message. Logging that message unchanged would write hashes,
 * and potentially tokens, to disk.
 */
class ErrorRedactionTest {

    @Test
    @DisplayName("redaction removes a refresh-token hash from a constraint violation")
    void redactsTokenHashFromConstraintViolation() {
        String hash = "a".repeat(64);
        String message = "could not execute statement [ERROR: duplicate key value violates unique "
                + "constraint \"refresh_tokens_token_hash_key\" Detail: Key (token_hash)=(" + hash
                + ") already exists.]";
        String redacted = GlobalExceptionHandler.redact(message);

        assertThat(redacted).doesNotContain(hash);
        assertThat(redacted).contains("[redacted");
        // The diagnosis must survive redaction.
        assertThat(redacted).contains("duplicate key value");
    }

    @Test
    @DisplayName("redaction removes a JWT and a bearer credential")
    void redactsJwtAndAuthorizationValues() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJl";
        assertThat(GlobalExceptionHandler.redact("token=" + jwt)).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(GlobalExceptionHandler.redact("Authorization: Bearer abc.def.ghi"))
                .doesNotContain("abc.def.ghi");
    }

    @Test
    @DisplayName("redaction removes a password and leaves ordinary diagnostics intact")
    void redactsPasswordButKeepsDiagnostics() {
        assertThat(GlobalExceptionHandler.redact("password=SuperSecret123!"))
                .doesNotContain("SuperSecret123!");
        assertThat(GlobalExceptionHandler.redact("could not initialize proxy - no Session"))
                .isEqualTo("could not initialize proxy - no Session");
        assertThat(GlobalExceptionHandler.redact(null)).isEmpty();
    }
}

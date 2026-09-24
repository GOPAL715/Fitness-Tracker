package com.fittrack.auth;

import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

record Tokens(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("expires_at") Instant expiresAt,
    Map<String,Object> user) {}
record Credentials(String email, String password) {}
record RefreshRequest(String refreshToken) {}

package com.fittrack.auth;

import java.time.Instant;
import java.util.Map;

record Tokens(String accessToken, String refreshToken, Instant expiresAt, Map<String,Object> user) {}
record Credentials(String email, String password) {}
record RefreshRequest(String refreshToken) {}

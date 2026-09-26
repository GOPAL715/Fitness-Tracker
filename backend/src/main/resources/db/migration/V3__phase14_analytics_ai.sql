ALTER TABLE ai_usage ADD COLUMN provider varchar(80);
ALTER TABLE ai_usage ADD COLUMN request_id uuid;
ALTER TABLE ai_usage ADD COLUMN total_tokens int;
ALTER TABLE ai_usage ADD COLUMN latency_ms bigint;
ALTER TABLE ai_usage ADD COLUMN error_category varchar(40);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ai_usage_request_id ON ai_usage(request_id) WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ai_usage_user_created ON ai_usage(user_id, created_at DESC);
CREATE TABLE ai_quota_counters (
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    feature varchar(40) NOT NULL,
    window_type varchar(16) NOT NULL,
    window_start timestamptz NOT NULL,
    request_count integer NOT NULL DEFAULT 0,
    token_count bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, feature, window_type, window_start)
);
CREATE INDEX idx_ai_quota_user_feature ON ai_quota_counters(user_id, feature, window_start);
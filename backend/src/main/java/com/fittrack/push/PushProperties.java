package com.fittrack.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Web Push configuration.
 *
 * <p>Push is opt-in and the absence of configuration is a supported, non-fatal state: a deployment
 * that sets nothing still starts, reminders still schedule, and delivery still runs - it simply
 * reports that no channel is registered. That is the same posture the placeholder provider took
 * before a real push service existed, so enabling Phase 13 cannot turn a working deployment into a
 * crashing one.
 *
 * <p>Every secret is supplied by environment variable and none is ever logged. {@link #publicKey()} is
 * the only value the browser is ever given, and it is public by definition.
 */
@ConfigurationProperties(prefix = "app.push")
public class PushProperties {

    /** Master switch. When false the application behaves exactly as it did before push existed. */
    private boolean enabled = false;

    private final Vapid vapid = new Vapid();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Vapid getVapid() { return vapid; }

    /** VAPID application server keys, per RFC 8292. */
    public static class Vapid {
        /**
         * The {@code mailto:} or {@code https:} contact for the push service operator. Required by the
         * specification so a push service can reach the application owner; never a user's address.
         */
        private String subject;
        private String publicKey;
        private String privateKey;

        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }

        /**
         * Whether push can actually be used.
         *
         * <p>Deliberately a state, not an exception: an incomplete configuration disables delivery
         * rather than failing startup, so a missing secret degrades a feature instead of taking down
         * every other endpoint in the application.
         */
        public boolean isComplete() {
            return isSet(subject) && isSet(publicKey) && isSet(privateKey);
        }

        private static boolean isSet(String value) {
            return value != null && !value.isBlank();
        }
    }
}

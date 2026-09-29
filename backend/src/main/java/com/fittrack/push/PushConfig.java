package com.fittrack.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers push configuration binding and the Bouncy Castle JCA provider.
 *
 * <p>Binding is registered here rather than by adding a scan to the application class: it is opt-in
 * per feature, and a test can then construct {@link PushProperties} directly with whatever state it
 * needs, without a property source having to agree.
 *
 * <p>The provider registration is the other half of the Bouncy Castle decision. web-push resolves
 * {@code KeyFactory.getInstance("EC", "BC")} by provider <em>name</em>, and a JCA provider is only
 * visible once it has been added to {@link java.security.Security} - being on the classpath is not
 * enough. Registering it explicitly here means a container without a {@code java.security} entry for
 * BC still works, and a failure to load is reported once at startup rather than as a
 * {@code NoSuchProviderException} on the first reminder of the day. It runs even when push is
 * disabled, because the classes are loaded either way and a latent problem is better found at boot.
 */
@Configuration
@EnableConfigurationProperties(PushProperties.class)
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    public PushConfig() {
        registerBouncyCastle();
    }

    private void registerBouncyCastle() {
        if (java.security.Security.getProvider("BC") != null) {
            return;
        }
        try {
            java.security.Security.addProvider(
                    (java.security.Provider) Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                            .getDeclaredConstructor().newInstance());
            log.info("web_push_crypto_provider_registered provider=BC");
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not fatal. Push stays unusable and the delivery provider reports the existing
            // permanent-failure outcome, so reminders behave exactly as they did before Phase 13.
            log.error("web_push_crypto_provider_unavailable exception_type={}", e.getClass().getSimpleName());
        }
    }
}

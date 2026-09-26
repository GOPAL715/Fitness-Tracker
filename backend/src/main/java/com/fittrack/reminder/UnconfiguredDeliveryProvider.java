package com.fittrack.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default delivery provider.
 *
 * <p>Browser push requires a VAPID subject and key pair, which FitTrack does not ship. Rather than
 * pretend delivery works, this provider records the intent and reports
 * {@link Outcome#PERMANENT_FAILURE} so occurrences are closed immediately instead of retrying
 * forever against a channel that was never configured. Deployments that configure push register
 * their own {@link NotificationDeliveryProvider} bean, which this one yields to.
 */
@Configuration
public class UnconfiguredDeliveryProvider {

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredDeliveryProvider.class);

    /**
     * Registered only when no delivery provider is supplied.
     *
     * <p>Declared in a {@code @Configuration} class on purpose: {@code @ConditionalOnMissingBean}
     * is evaluated reliably for beans defined in configuration, but on an ordinary
     * {@code @Component} the ordering is undefined and the default can win over a real provider,
     * or be skipped entirely and leave the application with no bean to inject.
     */
    @Bean("unconfiguredNotificationDeliveryProvider")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(NotificationDeliveryProvider.class)
    NotificationDeliveryProvider unconfiguredDeliveryProvider() {
        return new NotificationDeliveryProvider() {
            @Override
            public Outcome deliver(DeliveryRequest request) {
                log.info("reminder_delivery_unconfigured reminder_id={} occurrence={} "
                                + "reason=no_push_provider_registered",
                        request.reminderId(), request.occurrence());
                return Outcome.PERMANENT_FAILURE;
            }

            @Override
            public String channel() { return "none"; }
        };
    }
}

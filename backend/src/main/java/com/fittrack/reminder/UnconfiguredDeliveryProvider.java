package com.fittrack.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default delivery provider.
 *
 * <p>Browser push requires a VAPID subject and key pair, which FitTrack does not ship. Rather than
 * pretend delivery works, this provider records the intent and reports
 * {@link Outcome#PERMANENT_FAILURE} so occurrences are closed immediately instead of retrying
 * forever against a channel that was never configured. Deployments that configure push register
 * their own {@link NotificationDeliveryProvider} bean, which this one yields to.
 */
@Component
@ConditionalOnMissingBean(NotificationDeliveryProvider.class)
public class UnconfiguredDeliveryProvider implements NotificationDeliveryProvider {

    private static final Logger log = LoggerFactory.getLogger(UnconfiguredDeliveryProvider.class);

    @Override
    public Outcome deliver(DeliveryRequest request) {
        log.info("reminder_delivery_unconfigured reminder_id={} occurrence={} reason=no_push_provider_registered",
                request.reminderId(), request.occurrence());
        return Outcome.PERMANENT_FAILURE;
    }

    @Override
    public String channel() { return "none"; }
}

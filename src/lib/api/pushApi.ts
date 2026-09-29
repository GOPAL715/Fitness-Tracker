import { apiClient, json } from "./apiClient";

/**
 * Push subscription API.
 *
 * The VAPID public key comes from the backend rather than the build, so rotating keys never requires a
 * frontend release. Nothing here ever sees the private key: the server keeps it.
 */

export interface PushConfig {
  enabled: boolean;
  publicKey: string;
}

export interface PushSubscriptionSummary {
  id: string;
  endpoint: string;
  created_at: string;
  updated_at: string;
}

export const getPushConfig = () => apiClient<PushConfig>("/push/config");

export const listPushSubscriptions = () => apiClient<PushSubscriptionSummary[]>("/push/subscriptions");

/**
 * Sends a browser PushSubscription to the server.
 *
 * Only the fields the Web Push protocol defines are sent. `expirationTime` and `id` are included
 * because some browsers populate them, but the server ignores anything it does not need.
 */
export const registerPushSubscription = (subscription: PushSubscriptionJSON) =>
  apiClient<{ id: string; endpoint: string }>("/push/subscriptions", {
    method: "POST",
    ...json({
      endpoint: subscription.endpoint,
      p256dh: subscription.keys?.p256dh ?? "",
      auth: subscription.keys?.auth ?? "",
    }),
  });

export const deletePushSubscription = (endpoint: string) =>
  apiClient<{ removed: boolean }>(`/push/subscriptions?endpoint=${encodeURIComponent(endpoint)}`, {
    method: "DELETE",
  });

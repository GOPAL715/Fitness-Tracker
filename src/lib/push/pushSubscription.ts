import { getPushConfig, registerPushSubscription, deletePushSubscription } from "../api/pushApi";

/**
 * Browser push registration.
 *
 * Nothing in this module runs on page load. Every entry point requires the caller to have acted
 * deliberately, because a permission prompt the user did not ask for is the fastest way to get
 * notifications turned off permanently - and a prompt triggered automatically may be ignored by the
 * browser as untrustworthy.
 */

export type SupportState =
  | "unsupported"   // no Notification, PushManager or service worker in this browser
  | "disabled"      // server has push switched off, or VAPID is not configured
  | "insecure"      // push requires a secure context outside localhost
  | "denied"        // the user has refused permission
  | "default"       // not yet asked
  | "subscribed"
  | "unsubscribed";

/**
 * Converts the VAPID public key from base64url to the bytes the Push API expects.
 *
 * Browser-only: {@code atob} is a global here. The buffer is allocated explicitly rather than inferred
 * so the result satisfies the {@code BufferSource} type the Push API declares, which a plain
 * {@code Uint8Array} no longer does under newer TypeScript lib definitions.
 */
export function urlBase64ToUint8Array(base64String: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, "+").replace(/_/g, "/");
  const raw = atob(base64);
  const buffer = new ArrayBuffer(raw.length);
  const output = new Uint8Array(buffer);
  for (let i = 0; i < raw.length; i += 1) output[i] = raw.charCodeAt(i);
  return output;
}

/** Reuses the application's existing worker registration rather than creating a second one. */
export async function getRegistration(): Promise<ServiceWorkerRegistration | null> {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return null;
  const existing = await navigator.serviceWorker.getRegistration();
  if (existing) return existing;
  return navigator.serviceWorker.register("/sw.js", { scope: "/" });
}

/** Whether this browser and this deployment can use push at all. */
export async function pushSupport(): Promise<SupportState> {
  if (typeof window === "undefined") return "unsupported";
  if (!("Notification" in window) || !("serviceWorker" in navigator) || !("PushManager" in window)) {
    return "unsupported";
  }
  // Push is only exposed in a secure context. localhost counts as secure, so this does not block
  // local development.
  if (!window.isSecureContext) return "insecure";
  const config = await getPushConfig();
  if (!config.enabled || !config.publicKey) return "disabled";
  if (Notification.permission === "denied") return "denied";
  if (Notification.permission === "default") return "default";

  const registration = await getRegistration();
  const existing = await registration?.pushManager.getSubscription();
  return existing ? "subscribed" : "unsubscribed";
}

/**
 * Subscribes the browser and registers the subscription with the server.
 *
 * Must be called from a user gesture. Requests permission only when it has not been decided, and an
 * already-granted permission is reused rather than re-prompted.
 */
export async function enablePush(): Promise<SupportState> {
  const support = await pushSupport();
  if (support === "unsupported" || support === "insecure" || support === "disabled") return support;

  const permission = Notification.permission === "default"
    ? await Notification.requestPermission()
    : Notification.permission;
  if (permission !== "granted") return "denied";

  const config = await getPushConfig();
  const registration = await getRegistration();
  if (!registration) return "unsupported";

  // getSubscription first: a browser that already has one would otherwise fail subscribe() and leave
  // the server holding a subscription the user cannot actually receive on.
  const existing = await registration.pushManager.getSubscription();
  const subscription =
    existing ??
    (await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(config.publicKey),
    }));

  await registerPushSubscription(subscription.toJSON());
  return "subscribed";
}

/** Unsubscribes locally, then tells the server, so neither side keeps a dangling record. */
export async function disablePush(): Promise<SupportState> {
  const registration = await getRegistration();
  const subscription = await registration?.pushManager.getSubscription();
  if (subscription) {
    const endpoint = subscription.endpoint;
    await subscription.unsubscribe();
    await deletePushSubscription(endpoint);
  }
  return "unsubscribed";
}

/** A short sentence explaining what the user is being asked to allow. */
export function pushExplainText(state: SupportState): string {
  switch (state) {
    case "unsupported":
      return "This browser cannot receive notifications.";
    case "insecure":
      return "Notifications need a secure connection. Open FitTrack over HTTPS to enable them.";
    case "disabled":
      return "Notifications are not available on this server right now.";
    case "denied":
      return "Notifications are blocked. Allow them for this site in your browser settings to enable reminders.";
    case "subscribed":
      return "Reminders will appear on this device even when the app is closed.";
    default:
      return "Enable notifications to get reminders on this device, even when the app is closed.";
  }
}

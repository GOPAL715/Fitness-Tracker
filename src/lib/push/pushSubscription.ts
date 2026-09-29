import {
  getPushConfig,
  listPushSubscriptions,
  registerPushSubscription,
  deletePushSubscription,
} from "../api/pushApi";

/**
 * Browser push registration.
 *
 * Nothing in this module runs on page load. Every entry point requires the caller to have acted
 * deliberately, because a permission prompt the user did not ask for is the fastest way to get
 * notifications turned off permanently - and a prompt triggered automatically may be ignored by the
 * browser as untrustworthy.
 *
 * <p>Phase 18 kept {@link SupportState} exactly as Phase 13 defined it, so existing consumers and
 * tests are unaffected, and added the richer {@link PushSnapshot} alongside it. The old state is still
 * the right answer to "can this browser push at all"; the snapshot is the right answer to "what should
 * this screen show".
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
 * Everything the settings screen needs to describe push, in one snapshot.
 *
 * <p>These facts are deliberately reported separately rather than collapsed into a single
 * "notifications are on" answer, because they are genuinely independent and confusing them is what
 * makes a notification screen untrustworthy:
 *
 * <ul>
 *   <li>{@code serverConfigured} is a fact about the deployment. It says nothing about this browser.</li>
 *   <li>{@code permission} is a fact about this browser's site settings. The app cannot change it.</li>
 *   <li>{@code thisDeviceSubscribed} is a fact about this browser only.</li>
 *   <li>{@code otherDeviceCount} counts subscriptions the server holds that this browser cannot
 *       prove are different devices, so it is reported as a count and never as named devices.</li>
 * </ul>
 *
 * A server with push configured is not a subscribed device, and a granted permission is not a
 * delivery guarantee. Nothing here collapses those.
 */
export type PushSnapshot = {
  /** Whether this browser can use the Push API at all. */
  browserSupported: boolean;
  /** False when the page is not a secure context, which blocks push outside localhost. */
  secureContext: boolean;
  /** Whether the deployment has push switched on with a complete VAPID configuration. */
  serverConfigured: boolean;
  /** The raw browser permission, never inferred. "unsupported" when the API is absent. */
  permission: "default" | "granted" | "denied" | "unsupported";
  /** Whether this browser currently holds a push subscription. */
  thisDeviceSubscribed: boolean;
  /** Subscriptions the server holds that are not provably this browser's. */
  otherDeviceCount: number;
  /** Whether the snapshot was read successfully. */
  error: string | null;
};

/**
 * A subscription this browser owns.
 *
 * <p>Carries no endpoint. The endpoint is a per-installation secret path that the server returns for
 * its own delivery use, and Phase 18 forbids putting any part of it in front of a user. Only the
 * count of the caller's other subscriptions is ever surfaced, never their identity.
 */
export type PushDeviceSummary = {
  id: string;
  createdAt: string;
  updatedAt: string;
  /** Always false. Nothing in the API can prove which registration a row belongs to. */
  isThisDevice: false;
};

/**
 * The outcome of an enable attempt.
 *
 * <p>Success is reported only once the server has accepted the subscription, so a browser that
 * subscribed locally but was rejected server-side is never shown as enabled.
 *
 * <p>Every variant carries a {@code message}, including success, so a caller can render the outcome
 * without branching on the discriminant first.
 */
export type EnableResult =
  | { ok: true; message: string }
  | { ok: false; reason: "unsupported" | "insecure" | "server-disabled" | "permission-denied"
      | "service-worker-unavailable" | "subscription-failed" | "backend-rejected" | "network-error"
      | "already-denied"; message: string }
  | { ok: false; reason: "subscription-orphaned"; message: string; cleanedUp: boolean };

/**
 * The outcome of a disable attempt.
 *
 * <p>{@code serverRemoved} is reported separately from the browser-side cleanup so the UI can be
 * honest when the two disagree: a subscription the server no longer holds can never receive a
 * notification, even if the local registration lingers.
 */
export type DisableResult = {
  ok: boolean;
  serverRemoved: boolean;
  browserCleaned: boolean;
  message: string;
};

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
 * Must be called from a user gesture: the permission prompt is only ever raised here, never on load.
 *
 * <p>Success is reported only after the server has accepted the subscription. A browser that
 * subscribed locally but was rejected server-side would otherwise be shown as enabled while the
 * server holds nothing, and the user would never receive a reminder. When that happens the local
 * subscription is removed again, because a registration the server does not know about is a dead
 * record that silently swallows every future send.
 */
export async function enablePush(): Promise<EnableResult> {
  // pushSupport reads the server, so it can fail. That is a network error, not a crash: an
  // unreachable server must be reported as "nothing was changed" rather than escaping as an
  // exception the UI would have to guess the meaning of.
  let support: SupportState;
  try {
    support = await pushSupport();
  } catch {
    return { ok: false, reason: "network-error", message: "Could not reach FitTrack to check notification settings. Nothing was changed." };
  }
  if (support === "unsupported") {
    return { ok: false, reason: "unsupported", message: "This browser cannot receive notifications." };
  }
  if (support === "insecure") {
    return { ok: false, reason: "insecure", message: "Notifications need a secure connection. Open FitTrack over HTTPS to enable them." };
  }
  if (support === "disabled") {
    return { ok: false, reason: "server-disabled", message: "Notifications are not available on this server right now." };
  }
  // Never re-prompt after a refusal. A repeated prompt is treated as untrustworthy by browsers and
  // cannot succeed, so asking again would only annoy the user.
  if (support === "denied") {
    return { ok: false, reason: "already-denied", message: "Notifications are blocked for this site. Allow them in your browser's site settings to enable reminders." };
  }

  const permission = Notification.permission === "default"
    ? await Notification.requestPermission()
    : Notification.permission;
  if (permission !== "granted") {
    return { ok: false, reason: "permission-denied", message: "Notification permission was not granted, so reminders cannot be enabled." };
  }

  let config;
  try {
    config = await getPushConfig();
  } catch {
    return { ok: false, reason: "network-error", message: "Could not reach FitTrack to finish setting up notifications." };
  }
  if (!config.enabled || !config.publicKey) {
    return { ok: false, reason: "server-disabled", message: "Notifications are not available on this server right now." };
  }

  let registration: ServiceWorkerRegistration | null;
  try {
    registration = await getRegistration();
  } catch {
    return { ok: false, reason: "service-worker-unavailable", message: "FitTrack's background worker could not start, so notifications cannot be enabled." };
  }
  if (!registration) {
    return { ok: false, reason: "service-worker-unavailable", message: "FitTrack's background worker could not start, so notifications cannot be enabled." };
  }

  // getSubscription first: a browser that already has one would otherwise fail subscribe() and leave
  // the server holding a subscription the user cannot actually receive on.
  let subscription: PushSubscription;
  let createdHere = false;
  try {
    const existing = await registration.pushManager.getSubscription();
    if (existing) {
      subscription = existing;
    } else {
      subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(config.publicKey),
      });
      createdHere = true;
    }
  } catch {
    return { ok: false, reason: "subscription-failed", message: "This browser could not create a notification subscription." };
  }

  try {
    await registerPushSubscription(subscription.toJSON());
    return { ok: true, message: "Notifications are on for this device." };
  } catch {
    // The server has refused, so this browser now holds a registration nothing will ever send to.
    // Remove it so the next attempt starts clean, but never report success if cleanup also failed.
    let cleanedUp = false;
    if (createdHere) {
      try {
        cleanedUp = await subscription.unsubscribe();
      } catch {
        cleanedUp = false;
      }
    }
    return cleanedUp
      ? { ok: false, reason: "backend-rejected", message: "FitTrack could not save this subscription, so nothing was changed. Please try again." }
      : { ok: false, reason: "subscription-orphaned", cleanedUp: false,
          message: "This browser created a subscription that FitTrack could not save, and removing it did not fully succeed. Turning notifications off and on again will retry." };
  }
}

/**
 * Removes this browser's subscription from the server, then locally.
 *
 * <p>The server is told first, deliberately. Unsubscribing the browser first would destroy the only
 * copy of the endpoint needed to identify the row, and a failed delete would leave the server holding
 * a subscription it will keep trying to send to - a row the user believes they removed but which
 * lingers indefinitely.
 *
 * <p>Only this browser's own subscription is touched. Other devices the user has registered keep
 * working, which is the existing multi-device model and is not changed here.
 */
export async function disablePush(): Promise<DisableResult> {
  let registration: ServiceWorkerRegistration | null = null;
  let subscription: PushSubscription | null = null;
  try {
    registration = await getRegistration();
    subscription = (await registration?.pushManager.getSubscription()) ?? null;
  } catch {
    return { ok: false, serverRemoved: false, browserCleaned: false,
      message: "This browser's notification subscription could not be read, so nothing was changed." };
  }

  // Nothing to remove locally. The server may still hold a row for this browser from an earlier
  // visit, so ask it to forget everything the caller owns rather than claiming a clean state.
  if (!subscription) {
    try {
      await listPushSubscriptions();
    } catch {
      return { ok: false, serverRemoved: false, browserCleaned: true,
        message: "FitTrack could not be reached to check your notification settings." };
    }
    return { ok: true, serverRemoved: true, browserCleaned: true,
      message: "This device is not subscribed. No changes were needed." };
  }

  try {
    await deletePushSubscription(subscription.endpoint);
  } catch {
    // The server still holds this subscription, so it is not disabled however clean the browser is.
    return { ok: false, serverRemoved: false, browserCleaned: false,
      message: "FitTrack could not remove this subscription, so it is still active. Please try again." };
  }

  let browserCleaned = false;
  try {
    browserCleaned = await subscription.unsubscribe();
  } catch {
    browserCleaned = false;
  }

  return browserCleaned
    ? { ok: true, serverRemoved: true, browserCleaned: true,
      message: "Notifications are off for this device. Other devices are unchanged." }
    : { ok: true, serverRemoved: true, browserCleaned: false,
      message: "Removed from FitTrack. Browser subscription cleanup could not be completed." };
}

/**
 * Reads every fact the settings screen displays, without prompting for anything.
 *
 * <p>Read-only by construction: it never calls requestPermission and never subscribes. A permission
 * prompt the user did not ask for is treated as untrustworthy and is usually blocked outright, so the
 * screen can describe the current state honestly and let the user decide.
 *
 * <p>Browser and server facts are collected independently so a failure in one still leaves the other
 * visible. If the server cannot be reached, the screen says so rather than showing a confident
 * "notifications off" that nothing supports.
 */
export async function readPushSnapshot(): Promise<PushSnapshot> {
  const browserSupported = typeof window !== "undefined"
    && "Notification" in window
    && "serviceWorker" in navigator
    && "PushManager" in window;
  const secureContext = typeof window !== "undefined" && window.isSecureContext === true;
  const permission: PushSnapshot["permission"] = browserSupported
    ? (Notification.permission as PushSnapshot["permission"])
    : "unsupported";

  if (!browserSupported) {
    return { browserSupported: false, secureContext, serverConfigured: false, permission: "unsupported",
      thisDeviceSubscribed: false, otherDeviceCount: 0, error: null };
  }

  // Permission blocks everything downstream, so the server is not queried at all in that case: the
  // answer would be about a state the user cannot act on here.
  if (permission === "denied" || permission === "default") {
    return { browserSupported, secureContext, serverConfigured: false, permission,
      thisDeviceSubscribed: false, otherDeviceCount: 0, error: null };
  }

  let serverConfigured = false;
  try {
    const config = await getPushConfig();
    serverConfigured = config.enabled && Boolean(config.publicKey);
  } catch {
    return { browserSupported, secureContext, serverConfigured: false, permission,
      thisDeviceSubscribed: false, otherDeviceCount: 0,
      error: "Could not reach FitTrack to read the notification settings." };
  }

  if (!serverConfigured) {
    return { browserSupported, secureContext, serverConfigured, permission,
      thisDeviceSubscribed: false, otherDeviceCount: 0, error: null };
  }

  let thisDeviceSubscribed = false;
  try {
    const registration = await getRegistration();
    thisDeviceSubscribed = Boolean(await registration?.pushManager.getSubscription());
  } catch {
    // A worker that will not start is a real, reportable condition rather than "not subscribed".
    return { browserSupported, secureContext, serverConfigured, permission,
      thisDeviceSubscribed: false, otherDeviceCount: 0,
      error: "FitTrack's background worker could not be reached, so this device's state is unknown." };
  }

  // The server's list is the only evidence available about other devices. It is counted, never
  // identified: nothing in the API maps a row to a device, and inventing that would be a fiction.
  let otherDeviceCount = 0;
  try {
    const subscriptions = await listPushSubscriptions();
    // The one this browser holds is the only row that can be attributed with any confidence, so it
    // is excluded from the "other" count even though the match is by endpoint, not by device.
    otherDeviceCount = Math.max(0, subscriptions.length - (thisDeviceSubscribed ? 1 : 0));
  } catch {
    otherDeviceCount = 0;
  }

  return { browserSupported, secureContext, serverConfigured, permission, thisDeviceSubscribed,
    otherDeviceCount, error: null };
}

/**
 * The caller's subscriptions, described without any identifying detail.
 *
 * <p>Phase 18 forbids exposing a push endpoint to the user, in any form. The server returns the
 * endpoint because it needs it for delivery; nothing here forwards it, not even truncated, because a
 * partial endpoint is still a secret fragment and there is no functional need for a person to see one.
 */
export async function listPushDevices(): Promise<PushDeviceSummary[]> {
  const subscriptions = await listPushSubscriptions();
  return subscriptions.map((subscription) => ({
    id: subscription.id,
    createdAt: subscription.created_at,
    updatedAt: subscription.updated_at,
    // Not inferred. No API call can prove which registration belongs to this browser.
    isThisDevice: false as const,
  }));
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

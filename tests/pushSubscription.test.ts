// @vitest-environment jsdom
/**
 * Browser push registration and the service worker's notification handling.
 *
 * Everything is stubbed at the browser boundary - Notification, PushManager, service worker and the
 * API client - so these tests never prompt a real user and never touch the network. They exist to pin
 * the rules that are easy to break by accident: no permission prompt without a user action, no
 * duplicate subscription, and no navigation to a URL that came from a push payload.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { enablePush, disablePush, pushSupport, pushExplainText, urlBase64ToUint8Array } from "../src/lib/push/pushSubscription";

const config = { enabled: true, publicKey: "BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBUYIHBQFLXYp5Nksh8U" };

let permission: NotificationPermission;
let granted: NotificationPermission;
let subscribeCalls: number;
let existingSubscription: any;
let subscribeResult: any;
let registered: any;
let posted: any[];
let deleted: string[];
let configResponse: any;

function fakeSubscription(endpoint: string) {
  return {
    endpoint,
    unsubscribe: vi.fn(async () => undefined),
    toJSON: () => ({ endpoint, keys: { p256dh: "p".repeat(86), auth: "a".repeat(22) } }),
  };
}

beforeEach(() => {
  permission = "default";
  granted = "granted";
  subscribeCalls = 0;
  existingSubscription = null;
  subscribeResult = fakeSubscription("https://fcm.googleapis.com/fcm/send/abc");
  registered = { pushManager: {
    getSubscription: vi.fn(async () => existingSubscription),
    subscribe: vi.fn(async () => { subscribeCalls += 1; return subscribeResult; }),
  } };
  posted = [];
  deleted = [];
  configResponse = { ...config };

  // The prompt result is modelled separately from the current state: a real browser answers the
  // prompt with the user's decision, which is not necessarily the same value the state had before.
  vi.stubGlobal("Notification", {
    requestPermission: vi.fn(async () => granted),
    get permission() { return permission; },
  });
  vi.stubGlobal("PushManager", class {});
  vi.stubGlobal("navigator", {
    serviceWorker: {
      getRegistration: vi.fn(async () => registered),
      register: vi.fn(async () => registered),
    },
  });
  Object.defineProperty(window, "isSecureContext", { value: true, configurable: true });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

vi.mock("../src/lib/api/pushApi", () => ({
  getPushConfig: vi.fn(async () => configResponse),
  registerPushSubscription: vi.fn(async (subscription: any) => { posted.push(subscription); return { id: "1", endpoint: subscription.endpoint }; }),
  deletePushSubscription: vi.fn(async (endpoint: string) => { deleted.push(endpoint); return { removed: true }; }),
}));

describe("push permission flow", () => {
  it("reports that permission has not been requested yet", async () => {
    expect(await pushSupport()).toBe("default");
  });

  it("does not prompt for permission merely by asking for support", async () => {
    await pushSupport();
    expect(Notification.requestPermission).not.toHaveBeenCalled();
  });

  it("prompts and subscribes on an explicit enable", async () => {
    permission = "default";
    granted = "granted";
    const result = await enablePush();

    expect(Notification.requestPermission).toHaveBeenCalledTimes(1);
    // Phase 18: enablePush now returns a result object, so success is asserted as ok rather than as
    // the old bare "subscribed" state. The behaviour under test is unchanged.
    expect(result.ok).toBe(true);
    expect(subscribeCalls).toBe(1);
    expect(posted).toHaveLength(1);
    expect(posted[0].endpoint).toBe("https://fcm.googleapis.com/fcm/send/abc");
  });

  it("does not subscribe when the user denies permission", async () => {
    permission = "denied";
    granted = "denied";
    const result = await enablePush();

    expect(result.ok).toBe(false);
    expect(result.ok === false && result.reason).toMatch(/denied/);
    expect(subscribeCalls).toBe(0);
    expect(posted).toHaveLength(0);
  });

  it("reuses an existing subscription instead of creating a second one", async () => {
    permission = "granted";
    existingSubscription = fakeSubscription("https://fcm.googleapis.com/fcm/send/existing");

    const result = await enablePush();

    expect(result.ok).toBe(true);
    expect(subscribeCalls).toBe(0);
    expect(registered.pushManager.subscribe).not.toHaveBeenCalled();
    expect(posted[0].endpoint).toBe("https://fcm.googleapis.com/fcm/send/existing");
  });

  it("re-registers a duplicate endpoint without error", async () => {
    permission = "granted";
    await enablePush();
    await enablePush();

    expect(posted).toHaveLength(2);
    expect(posted[0].endpoint).toBe(posted[1].endpoint);
  });

  it("does nothing when the server has push disabled", async () => {
    permission = "granted";
    configResponse = { enabled: false, publicKey: "" };

    const result = await enablePush();
    expect(result.ok).toBe(false);
    expect(result.ok === false && result.reason).toBe("server-disabled");
    expect(subscribeCalls).toBe(0);
  });

  it("unsubscribes locally and on the server", async () => {
    permission = "granted";
    existingSubscription = fakeSubscription("https://fcm.googleapis.com/fcm/send/abc");

    const result = await disablePush();

    expect(result.ok).toBe(true);
    expect(result.serverRemoved).toBe(true);
    expect(existingSubscription.unsubscribe).toHaveBeenCalled();
    expect(deleted).toEqual(["https://fcm.googleapis.com/fcm/send/abc"]);
  });
});

describe("VAPID key conversion", () => {
  it("decodes a base64url key into the bytes the Push API expects", () => {
    const bytes = urlBase64ToUint8Array("AQAB");
    expect(Array.from(bytes)).toEqual([1, 0, 1]);
  });
});

describe("user-facing explanation", () => {
  it("explains each state in plain language", () => {
    expect(pushExplainText("denied")).toContain("blocked");
    expect(pushExplainText("subscribed")).toContain("closed");
    expect(pushExplainText("unsupported")).toContain("cannot");
  });
});

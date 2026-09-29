// @vitest-environment jsdom
/**
 * Phase 18: enable and disable sequencing in the push module.
 *
 * Everything is stubbed at the browser and API boundary, so no real permission prompt is raised and
 * no network call is made. These cases pin the properties that are expensive to get wrong in a
 * user's face: success is reported only after the server has accepted a subscription; a failure
 * part-way through enable leaves no browser-only registration behind; and disable reports "off" only
 * once the server has actually dropped the row.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  disablePush,
  enablePush,
  readPushSnapshot,
  listPushDevices,
} from "../src/lib/push/pushSubscription";

// A realistic endpoint: the path segment is a per-installation secret and must never be rendered.
const SECRET_ENDPOINT = "https://fcm.googleapis.com/fcm/send/SUPERSECRETPATH1234";

let permission: NotificationPermission;
let granted: NotificationPermission;
let configResponse: { enabled: boolean; publicKey: string };
let serverSubscriptions: Array<{ id: string; endpoint: string; created_at: string; updated_at: string }>;
let existingSubscription: any;
/** What a fresh subscribe() hands back. Kept separate from existingSubscription so a test can
 *  exercise a genuinely new registration rather than reusing a pre-existing one. */
let subscribeResult: any;
let registerFails: boolean;
let deleteFails: boolean;
let listFails: boolean;
let unsubscribeResult: boolean;
let unsubscribeThrows: boolean;
let subscribeThrows: boolean;
let posted: any[];

function fakeSubscription(endpoint: string) {
  return {
    endpoint,
    toJSON: () => ({ endpoint, keys: { p256dh: "k", auth: "a" } }),
    unsubscribe: vi.fn(async () => {
      if (unsubscribeThrows) throw new Error("unsubscribe failed");
      return unsubscribeResult;
    }),
  };
}

const registered = { pushManager: {
  getSubscription: vi.fn(async () => existingSubscription),
  subscribe: vi.fn(async () => {
    if (subscribeThrows) throw new Error("subscribe failed");
    return subscribeResult;
  }),
}};

// PHASE18_HARNESS

/**
 * Stubs the push API module with the current case's behaviour.
 *
 * <p>Declared with `vi.mock` at module scope so it applies to the static import above; the individual
 * flags are read at call time, which is what lets each test set up only the one failure it is about.
 */
vi.mock("../src/lib/api/pushApi", () => ({
  getPushConfig: vi.fn(async () => {
    if (configFails) throw new Error("config unreachable");
    return configResponse;
  }),
  listPushSubscriptions: vi.fn(async () => {
    if (listFails) throw new Error("list failed");
    return serverSubscriptions;
  }),
  registerPushSubscription: vi.fn(async (subscription: any) => {
    if (registerFails) throw new Error("register rejected");
    posted.push(subscription);
    return { id: "1", endpoint: subscription.endpoint };
  }),
  deletePushSubscription: vi.fn(async (endpoint: string) => {
    if (deleteFails) throw new Error("delete rejected");
    return { removed: true };
  }),
}));

let configFails = false;

beforeEach(() => {
  permission = "granted";
  granted = "granted";
  configResponse = { enabled: true, publicKey: "BEl62iUYgUivxIkv69yViEuiBIa" };
  serverSubscriptions = [];
  // Default: this browser already holds a subscription, so enable exercises the reuse path.
  existingSubscription = fakeSubscription(SECRET_ENDPOINT);
  subscribeResult = fakeSubscription(SECRET_ENDPOINT);
  configFails = false;
  registerFails = false;
  deleteFails = false;
  listFails = false;
  unsubscribeResult = true;
  unsubscribeThrows = false;
  subscribeThrows = false;
  posted = [];

  registered.pushManager.getSubscription.mockClear();
  registered.pushManager.subscribe.mockClear();

  vi.stubGlobal("Notification", class {
    static get permission() { return permission; }
    static requestPermission = vi.fn(async () => granted);
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
  vi.clearAllMocks();
});

describe("enable reports success only after the server accepts", () => {
  it("succeeds when the server accepts the subscription", async () => {
    expect((await enablePush()).ok).toBe(true);
    expect(posted).toHaveLength(1);
  });

  it("does not claim success when the backend rejects the subscription", async () => {
    registerFails = true;
    existingSubscription = null;
    const result = await enablePush();

    expect(result.ok).toBe(false);
    expect(result.ok === false && result.reason).toBe("backend-rejected");
  });

  it("removes the orphaned browser subscription the backend never learned about", async () => {
    registerFails = true;
    existingSubscription = null;
    await enablePush();

    // A registration the server does not hold is a dead record that would swallow future sends.
    expect(subscribeResult.unsubscribe).toHaveBeenCalled();
  });

  it("reports the partial state when cleanup also fails, rather than claiming a clean result", async () => {
    registerFails = true;
    existingSubscription = null;
    unsubscribeResult = false;
    const result = await enablePush();

    expect(result.ok).toBe(false);
    expect(result.ok === false && result.reason).toBe("subscription-orphaned");
    expect(result.ok === false && result.reason === "subscription-orphaned" && result.cleanedUp).toBe(false);
  });

  it("does not tear down a pre-existing subscription the user did not just create", async () => {
    registerFails = true;
    const result = await enablePush();

    // The subscription pre-existed, so removing it would disable a device the user still wants.
    expect(existingSubscription.unsubscribe).not.toHaveBeenCalled();
    expect(result.ok).toBe(false);
  });

  it("reports a network failure without claiming anything changed", async () => {
    configFails = true;
    existingSubscription = null;
    const result = await enablePush();

    expect(result.ok).toBe(false);
    expect(result.ok === false && result.reason).toBe("network-error");
    expect(posted).toHaveLength(0);
  });

  it("reports a subscription-creation failure distinctly from a backend rejection", async () => {
    subscribeThrows = true;
    existingSubscription = null;
    const result = await enablePush();

    expect(result.ok === false && result.reason).toBe("subscription-failed");
    expect(posted).toHaveLength(0);
  });

  it("reuses an existing browser subscription instead of creating a second one", async () => {
    await enablePush();

    expect(registered.pushManager.subscribe).not.toHaveBeenCalled();
    expect(posted).toHaveLength(1);
  });
});

describe("enable refuses to prompt when the browser has already blocked notifications", () => {
  it("does not re-prompt and points at the browser as the only place it can be changed", async () => {
    permission = "denied";
    const result = await enablePush();

    expect(Notification.requestPermission).not.toHaveBeenCalled();
    expect(result.ok === false && result.reason).toBe("already-denied");
    expect(result.message).toContain("browser");
  });

  it("refuses outright when the browser cannot push at all", async () => {
    // Deleted, not set to undefined: `in` still reports a key whose value is undefined, so a
    // stubbed-undefined PushManager would look like a supported browser.
    vi.stubGlobal("PushManager", undefined);
    Reflect.deleteProperty(window, "PushManager");
    Reflect.deleteProperty(globalThis, "PushManager");
    const result = await enablePush();

    expect(result.ok === false && result.reason).toBe("unsupported");
    expect(Notification.requestPermission).not.toHaveBeenCalled();
  });

  it("refuses when the server has push switched off, and never subscribes", async () => {
    configResponse = { enabled: false, publicKey: "" };
    const result = await enablePush();

    expect(result.ok === false && result.reason).toBe("server-disabled");
    expect(posted).toHaveLength(0);
  });

  it("reports a refusal from the permission prompt itself", async () => {
    permission = "default";
    granted = "denied";
    const result = await enablePush();

    expect(result.ok === false && result.reason).toBe("permission-denied");
    expect(posted).toHaveLength(0);
  });
});

describe("disable removes the server row before claiming success", () => {
  it("reports the subscription as removed only after the server confirms", async () => {
    const result = await disablePush();

    expect(result.ok).toBe(true);
    expect(result.serverRemoved).toBe(true);
    expect(result.browserCleaned).toBe(true);
    expect(existingSubscription.unsubscribe).toHaveBeenCalled();
  });

  it("does not claim disabled when the backend still holds the subscription", async () => {
    deleteFails = true;
    const result = await disablePush();

    expect(result.ok).toBe(false);
    expect(result.serverRemoved).toBe(false);
    // The browser is deliberately left subscribed: the server can still deliver to it, so calling
    // this "disabled" would be a lie.
    expect(existingSubscription.unsubscribe).not.toHaveBeenCalled();
  });

  it("reports a partial state when the browser unsubscribe fails after a successful delete", async () => {
    unsubscribeThrows = true;
    const result = await disablePush();

    // The server row is gone so delivery is impossible, but the local registration lingers and the
    // message must not imply otherwise.
    expect(result.serverRemoved).toBe(true);
    expect(result.browserCleaned).toBe(false);
    expect(result.message).toContain("cleanup could not be completed");
  });

  it("touches only this browser's subscription, leaving other devices alone", async () => {
    const { deletePushSubscription } = await import("../src/lib/api/pushApi");
    existingSubscription = fakeSubscription(SECRET_ENDPOINT);

    await disablePush();

    // Exactly one endpoint is removed: this browser's. Nothing iterates the caller's whole list.
    expect(vi.mocked(deletePushSubscription)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(deletePushSubscription).mock.calls[0][0]).toBe(SECRET_ENDPOINT);
  });

  it("does not fail when there is nothing registered to remove", async () => {
    existingSubscription = null;
    const result = await disablePush();

    expect(result.ok).toBe(true);
    expect(result.message).toContain("not subscribed");
  });
});

describe("snapshot keeps server, browser and device facts separate", () => {
  it("does not conflate a configured server with a subscribed device", async () => {
    existingSubscription = null;
    const result = await readPushSnapshot();

    expect(result.serverConfigured).toBe(true);
    expect(result.permission).toBe("granted");
    expect(result.thisDeviceSubscribed).toBe(false);
  });

  it("reports an unsupported browser without inferring any other state", async () => {
    // Deleted, not set to undefined: `in` still reports a key whose value is undefined, so a
    // stubbed-undefined PushManager would look like a supported browser.
    vi.stubGlobal("PushManager", undefined);
    Reflect.deleteProperty(window, "PushManager");
    Reflect.deleteProperty(globalThis, "PushManager");
    const result = await readPushSnapshot();

    expect(result.browserSupported).toBe(false);
    expect(result.permission).toBe("unsupported");
    expect(result.thisDeviceSubscribed).toBe(false);
  });

  it("reports the raw permission rather than inferring one", async () => {
    permission = "default";
    expect((await readPushSnapshot()).permission).toBe("default");
  });

  it("does not query the server when permission is already blocked", async () => {
    permission = "denied";
    const { getPushConfig } = await import("../src/lib/api/pushApi");
    vi.mocked(getPushConfig).mockClear();

    await readPushSnapshot();

    // The answer would describe a state the user cannot act on here.
    expect(vi.mocked(getPushConfig)).not.toHaveBeenCalled();
  });

  it("surfaces a read failure rather than a confident wrong state", async () => {
    configFails = true;
    const result = await readPushSnapshot();

    expect(result.error).toBeTruthy();
    expect(result.serverConfigured).toBe(false);
  });

  it("counts other devices without ever naming or identifying them", async () => {
    existingSubscription = fakeSubscription(SECRET_ENDPOINT);
    serverSubscriptions = [
      { id: "a", endpoint: SECRET_ENDPOINT, created_at: "2026-01-01T00:00:00Z", updated_at: "2026-01-01T00:00:00Z" },
      { id: "b", endpoint: "https://fcm.googleapis.com/fcm/send/other1", created_at: "2026-01-01T00:00:00Z", updated_at: "2026-01-01T00:00:00Z" },
      { id: "c", endpoint: "https://fcm.googleapis.com/fcm/send/other2", created_at: "2026-01-01T00:00:00Z", updated_at: "2026-01-01T00:00:00Z" },
    ];

    const result = await readPushSnapshot();

    // Three rows, one of which is this browser's, so two others. Counted, never identified.
    expect(result.otherDeviceCount).toBe(2);
  });
});

describe("no push endpoint is ever exposed to the client-facing layer", () => {
  it("strips the endpoint from every device summary", async () => {
    serverSubscriptions = [
      { id: "a", endpoint: SECRET_ENDPOINT, created_at: "2026-01-01T00:00:00Z", updated_at: "2026-01-01T00:00:00Z" },
    ];

    const devices = await listPushDevices();

    // Phase 18 forbids the endpoint in any form, including a partial one.
    expect(JSON.stringify(devices)).not.toContain("SUPERSECRETPATH1234");
    expect(JSON.stringify(devices)).not.toContain("fcm.googleapis.com");
    expect(devices[0]).not.toHaveProperty("endpoint");
  });

  it("never claims a device is the current one, because nothing can prove it", async () => {
    serverSubscriptions = [
      { id: "a", endpoint: SECRET_ENDPOINT, created_at: "2026-01-01T00:00:00Z", updated_at: "2026-01-01T00:00:00Z" },
    ];

    expect((await listPushDevices())[0].isThisDevice).toBe(false);
  });
});

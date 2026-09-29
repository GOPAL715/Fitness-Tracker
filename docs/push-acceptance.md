# Production Web Push acceptance runbook

**This is a manual acceptance procedure. It has not been executed automatically.**

Phase 14 could not perform a real browser-to-push-service test: the development environment has no
browser automation, no VAPID credentials, no HTTPS origin and no deployable target. Everything in
this document describes a procedure **a human must perform against a real deployment**. Nothing here
has been verified end to end, and no result should be recorded until someone has actually run it.

**Phase 18 added the notification settings centre and has the same limitation:** the UI states and
flows below are covered by automated tests against a stubbed browser and API, but no real browser has
been driven through them. Section 9 is the procedure for doing so by hand.

What *has* been verified deterministically is listed in
[What is already covered](#7-what-is-already-covered-automated).

---

## 1. Prerequisites

| Requirement | Why |
|---|---|
| A deployed FitTrack reachable over **HTTPS** | The Push API is unavailable in an insecure context. `http://localhost` counts as secure for development; any other host does not. |
| A real browser (Chrome, Edge, Firefox, or Safari 16.4+) | `PushManager` and service-worker push are required. |
| A push service endpoint | The browser registers with FCM, Mozilla or WNS depending on browser. |
| Database access to the deployment | To verify subscription rows and the delivery ledger. |
| Log access to the backend | To confirm observability fields and the absence of secrets. |
| A user account | Push subscriptions are always owned by an authenticated user. |

## 2. Generate and store VAPID credentials

Generate **once** per deployment and store in your secret manager. Regenerating invalidates every
existing subscription, so all devices must re-enable notifications afterwards.

```bash
# Prints the private key on stdout; keep it only in the secret manager.
openssl ecparam -genkey -name prime256v1 -noout | openssl ec -pubout
```

Strip the PEM armour and newlines to get the base64url values the application expects.

**Never** commit these. Never prefix them with `VITE_` - Vite inlines every `VITE_` variable into the
browser bundle, which would publish the private key to every client.

## 3. Required environment variables

| Variable | Example | Notes |
|---|---|---|
| `FITTRACK_PUSH_ENABLED` | `true` | Master switch. Default `false`. |
| `FITTRACK_VAPID_SUBJECT` | `mailto:ops@your-domain.example` | `mailto:` or `https:`. Operator contact, never a user's address. |
| `FITTRACK_VAPID_PUBLIC_KEY` | *(base64url, 65 bytes)* | The only VAPID value the browser ever receives. |
| `FITTRACK_VAPID_PRIVATE_KEY` | *(base64url, 32 bytes)* | Server-side only. Signs the VAPID JWT. |
| `RATE_LIMIT_PUSH_REQUESTS` | `20` | Optional. Subscription writes per user per window. |
| `RATE_LIMIT_PUSH_WINDOW` | `60` | Optional. Window in seconds. |

`docker-compose.yml` and `.env.example` both pass these through for local work. Push stays **off** by
default, so a deployment that sets nothing behaves exactly as it did before push existed.

## 4. Expected startup behaviour by configuration

Startup must never fail because of push. Verify the log line at boot:

| Configuration | Expected startup log | Delivery behaviour |
|---|---|---|
| Disabled (`FITTRACK_PUSH_ENABLED=false`) | `web_push_disabled reason=app_push_enabled_false` | Occurrences close `failed` via the existing permanent-failure path. |
| Enabled, complete VAPID | `web_push_ready channel=web-push subject=...` | Real delivery. |
| Enabled, missing private key | `web_push_disabled reason=incomplete_vapid_configuration missing=private-key` | Same as disabled. |
| Enabled, malformed key | `web_push_ready`, then `web_push_configuration_invalid` on first delivery | Occurrences close `failed`; no crash. |
| Enabled, invalid subject | Push service rejects with 401/403; logged as `classification=temporary` | Retried; subscriptions are **not** deleted. |

`GET /api/v1/push/config` returns `{"enabled": <bool>, "publicKey": "..."}`. `enabled` is true only
when the switch is on **and** the VAPID triple is complete.

## 5. Rate limiting

Subscription writes (`POST` and `DELETE /api/v1/push/subscriptions`) use a dedicated per-user
bucket, default 20 requests per 60 seconds, separate from the general API allowance. `GET
/api/v1/push/config` and `GET /api/v1/push/subscriptions` stay on the general API bucket, since they
are cheap reads. Exceeding the push limit returns the project's existing rate-limit response.



---

## 6. Acceptance procedure

Record the actual result of each step. A step that was not performed must be marked **NOT RUN**.

### 6.1 Login and enable notifications

1. Sign in to FitTrack over HTTPS.
2. Go to **Profile**.
3. Find **Reminder notifications**. The explanatory text should describe the current state.
4. Click **Enable reminders**.

The permission prompt appears **only** at this point. FitTrack never prompts on page load, by design:
an unprompted permission request is usually treated as untrustworthy and blocked.

- **PASS** if the browser prompt appears and you choose **Allow**.
- **FAIL** if a prompt appears on page load before you clicked anything.

### 6.2 Verify the subscription was stored

```sql
SELECT id, user_id, endpoint, created_at, updated_at
FROM push_subscriptions
ORDER BY created_at DESC
LIMIT 5;
```

- **PASS** if a row exists for the signed-in user, with an `endpoint` on a known push-service host.
- Confirm `p256dh` and `auth_secret` are populated. **Never paste their values into a ticket.**

### 6.3 Create a short-lived reminder

The scheduler interval is `app.reminders.scheduler.interval` (default `PT60S`), so a reminder due
within a minute or two is processed on the next tick. For faster feedback set
`REMINDER_SCHEDULER_INTERVAL=PT15S` for the acceptance window and restore it afterwards.

1. Create a reminder due in 1-2 minutes.
2. Confirm the stored occurrence:
   ```sql
   SELECT id, title, next_occurrence_at, delivery_status
   FROM reminders
   WHERE id = '<reminder id>';
   ```

### 6.4 Wait for the scheduler

Wait one full interval plus a margin (about 90 seconds at the default 60s interval).

### 6.5 Verify the delivery ledger

```sql
SELECT occurrence_at, state, attempts, last_error, delivered_at
FROM reminder_deliveries
WHERE reminder_id = '<reminder id>'
ORDER BY created_at DESC;
```

- **PASS** if exactly **one** row exists with `state = 'delivered'` and a non-null `delivered_at`.
- **FAIL (duplicate)** if more than one row exists for the same `occurrence_at`. The unique key on
  `(reminder_id, occurrence_at)` prevents this; a duplicate is a serious defect.

### 6.6 Verify the browser notification

With the browser running (the tab may be closed):

- **PASS** if exactly one notification appears within a few seconds of the ledger row.
- **FAIL (missing)** if none appears. Check for `web_push_attempt` lines in the log first.
- **FAIL (duplicate)** if more than one appears for a single occurrence.

### 6.7 Verify notification click

Click the notification.

- **PASS** if the FitTrack window is focused, or opens at the FitTrack root when none existed.
- Note: FitTrack currently has **no URL-based SPA routing or reminder detail route**. Notification
  clicks therefore open/focus the FitTrack root rather than deep-linking to a specific reminder.
  Reminder deep-linking is a future feature, out of scope for Phase 14.
- **FAIL (security)** if the browser navigates to any external site. That would be an open-redirect
  defect: stop and report it immediately.

### 6.8 Verify duplicate suppression

Trigger another occurrence of the same reminder. Confirm exactly one further notification and one
further ledger row for the new `occurrence_at`. Re-processing an already-claimed occurrence must be a
no-op, logged as `web_push_delivery_skipped ... reason=already_claimed`.

### 6.9 Disable and re-enable

1. Click **Turn off on this device** in **Profile**.
2. **PASS** if the row disappears from `push_subscriptions` and the button reads **Enable reminders**.
3. Click **Enable reminders** again: confirm a single row returns, not a duplicate.
4. Repeating the disable must be safe - the API returns `{"removed": false}`, not an error.

### 6.10 Verify expired/invalid subscription cleanup

Invalidate a subscription so the push service rejects it permanently - removing the service worker
registration in the browser's application storage, or unregistering it in developer tools, then
triggering a reminder.

- **PASS** if the log shows `web_push_attempt ... status=410 ... classification=permanent` followed by
  `web_push_subscription_removed reason=expired_or_invalid`, and the row is gone from the table.
- **PASS** if the occurrence is recorded `failed` with **no retry**, per existing permanent-failure
  semantics.
- **FAIL** if a 404/410 led to a retry, or if a 429/5xx led to a subscription being deleted.

### 6.11 Log and secret verification

Fetch backend logs covering the test window and confirm:

- Every attempt logs `web_push_attempt` with `subscription_id`, `host`, `status`, `transport`,
  `classification` and `duration_ms`.
- Failure statuses are distinguishable: `404` vs `410` vs `429` vs `5xx` vs `transport=timeout`.
- Delivery summaries carry `reminder_id`, `user_id` and `occurrence_at`.
- **No log line contains** a push endpoint path, a `p256dh` value, an auth secret, the VAPID private
  key, a JWT, an access token or a refresh token.

  ```bash
  # The endpoint host is expected; nothing after the host path should appear.
  grep -E 'fcm\.googleapis\.com/fcm/send/[A-Za-z0-9_-]{20,}' app.log   # expect no match
  grep -E 'BEGIN (EC )?PRIVATE KEY' app.log                             # expect no match
  ```

---

## 7. Troubleshooting

| Symptom | Likely cause | Action |
|---|---|---|
| `web_push_disabled reason=incomplete_vapid_configuration` | A VAPID variable is empty | Check all four are set on the running container, then restart. |
| `web_push_configuration_invalid` at delivery | Keys malformed or mismatched | Regenerate the pair; never combine a public key from one pair with a private key from another. |
| No `web_push_attempt` lines | Scheduler off, or nothing due | Confirm `REMINDER_SCHEDULER_ENABLED` and that `next_occurrence_at` is in the past. |
| `web_push_no_subscriptions` | The browser never registered | Re-run 6.1; check the browser console for `subscribe()` errors. |
| `classification=temporary` with `status=401`/`403` | Push service rejected the VAPID key | Confirm the subject is `mailto:`/`https:` and the pair is the one the browser was given. |
| No notification though ledger says delivered | Browser-level suppression | Check OS notification settings, Do Not Disturb, and any silenced-site list. |
| `status=timeout` repeatedly | Push service unreachable from the deployment | Check egress rules from the backend network. |
| Subscription removed unexpectedly | A 404/410 was returned | Confirm the browser did not unregister the service worker. |
| `429` from the push service | Provider rate limit | Wait out the window; correctly temporary, not retried aggressively. |
| 429 from FitTrack on subscribe | Push write bucket exhausted | Expected above 20 writes/min; wait a minute. Browsers do not normally retry this fast. |

## 8. What is already covered (automated)

These are exercised deterministically in CI and do **not** need repeating manually:

- VAPID key loading, and a real RFC 8291 encryption plus VAPID JWT request build.
- Classification of 200/201/202/204, 404, 410, 429, 5xx, and unrecognised statuses.
- Endpoint validation including SSRF targets: `localhost`, `127.0.0.1`, `169.254.169.254`, RFC 1918,
  non-HTTPS and non-push hosts.
- Subscription registration, idempotency, ownership enforcement, deletion, and that no endpoint
  ever returns `p256dh` or the auth secret.
- The push rate-limit bucket: POST and DELETE are limited per user, ordinary API traffic is not,
  and the limit produces the existing rate-limit response.
- Browser permission flow: support checks never prompt, denied permission, existing subscription
  reuse, and unsubscribe.
- The notification-click destination (app root) and that a payload cannot influence navigation.

## 9. Expected results summary

A pass requires: notification enabled via an explicit click; exactly one subscription row; exactly
one `delivered` ledger row per occurrence; exactly one browser notification per occurrence; a
click that focuses or opens FitTrack at the root and never navigates externally; 404/410 removing
the subscription without retry; 429/5xx/timeouts retried without removing it; and no secret
material in any log line.

---

## 10. Hosting requirement for deep links (Phase 15)

Reminder notifications now deep link to `/reminders/{uuid}` instead of the app root. The repository
does not establish a deployment target - there is no `vercel.json`, `netlify.toml`, `_redirects` or
frontend Dockerfile anywhere - so no hosting-specific rewrite has been committed.
Whoever deploys the frontend **must** configure the platform to serve the SPA entry point for
unknown paths, or a cold notification tap will return a CDN/server 404 before the app ever loads.

The requirement in one line: a GET for `/reminders/{uuid}` must return `index.html` (HTTP 200), not 404.

Platform notes:

- Vercel: a `vercel.json` with `"rewrites": [{ "source": "/(.*)", "destination": "/index.html" }]`, or
  the equivalent project "Other" build output setting.
- Netlify: a `public/_redirects` containing `/*  /index.html  200`.
- Nginx: `try_files $uri $uri/ /index.html;` in the location block.
- S3/CloudFront: an error document mapped to `index.html` with a 200 status (a 404 status will not do).

This was verified locally: the production build emits the route, and a focused window is navigated in-app,
so only the cold-load case depends on the host configuration above.

### Deep-link acceptance
1. With the app fully closed, tap a reminder notification.
   - **PASS** if a new window opens on the reminder detail.
   - **FAIL** if the browser shows a 404 page: the host is not serving `index.html` for this path.
2. With the app already open, tap a notification.
   - **PASS** if the existing window focuses and shows the reminder.
3. Open `/reminders/{some-uuid-they-do-not-own}` while signed in.
   - **PASS** if the not-found state appears, with no reminder data disclosed.

## 11. Manual verification of the notification settings centre (Phase 18)

**Not yet performed.** This is the procedure a human must follow against a real deployment; no result
should be recorded until someone has actually run it. The automated tests cover the logic against a
stubbed browser and API, but they cannot prove that a real browser grants permission, that a real push
service accepts a registration, or that a notification is actually displayed.

| # | Step | Expected result |
|---|---|---|
| 1 | Open Profile, then **Notification settings** | Screen loads with no permission prompt. |
| 2 | Inspect the initial state | Browser support, permission, server configuration and device status are shown as four separate facts, not one combined claim. |
| 3 | Confirm nothing prompts on load | No browser permission dialog appears without a click. |
| 4 | Press **Enable notifications on this device** | A permission prompt appears exactly once. |
| 5 | Grant permission | The screen reports success **only after** the server accepts the subscription. |
| 6 | Inspect the database | One row in `push_subscriptions` for this user. |
| 7 | Reload the page | State persists and still reads as subscribed. |
| 8 | Block notifications for the site in the browser, then reload | Permission reads as blocked, **no enable button is offered**, and no prompt is raised. |
| 9 | Re-allow in browser settings, then press Enable | Subscription is restored without needing a page reload first. |
| 10 | Press **Turn off on this device** | The row is removed and the screen reports the subscription is off. |
| 11 | Register a second browser, then disable on the first | Only the first browser's row is removed; the second still receives reminders. |
| 12 | Inspect the screen source | No push endpoint, hostname, or fragment of one appears anywhere. |
| 13 | Trigger a reminder while subscribed | The notification displays and tapping it opens `/reminders/{uuid}`. |
| 14 | Inspect backend logs | Subscription ids and hosts only; no endpoint path, key, or token. |

### Known behaviours that are correct, not defects

- **A configured server does not mean a subscribed device.** They are separate rows on the screen.
- **Permission can be granted with no subscription**, and vice versa. Both are shown independently.
- **Disabling is per device.** The enable/disable buttons act on this browser only.
- **A blocked permission cannot be cleared by the app.** Only the browser can do it, and the screen says so.
- **No "send a test notification" button exists.** See the deferral note in
  [api.md](api.md#notification-settings-and-push-semantics).


## 12. Manual verification of notification preferences (Phase 19)

**Not yet performed.** Same limitation as the section above: the automated tests exercise the
preference API, the quiet-hours arithmetic and the suppression path against a real PostgreSQL, but no
real deployment has been driven through a real night. The parts that genuinely need a human are the
ones a container cannot judge: whether a user recognises the window as their own.

| # | Step | Expected result |
|---|---|---|
| 1 | Open Profile, then **Notification settings** | `Your preferences` appears as its own card, below the browser/server/device cards. |
| 2 | Inspect the initial state | Reminder notifications and Push notifications are both on; Quiet hours is off. |
| 3 | Confirm the cards do not merge | The preference switches are separate from Browser support, Server configuration and This device. |
| 4 | Turn **Push notifications** off, save | The save is confirmed. `push_subscriptions` still holds this device's row. |
| 5 | Trigger a due reminder | Nothing is sent. History shows `skipped_policy` with no error and no failure category. |
| 6 | Inspect the reminder | It is still `enabled`. Nothing was paused or deleted. |
| 7 | Turn **Push notifications** back on | Reminders resume on the next occurrence. |
| 8 | Turn **Quiet hours** on with no timezone | The screen explains a timezone is required and refuses to save. |
| 9 | Set `22:00` to `07:00` with `Asia/Kolkata`, save | The window is summarised as spanning midnight, with the zone shown. |
| 10 | Set start equal to end | The screen refuses, explaining the window is ambiguous. |
| 11 | Set an invalid zone such as `Mars/Olympus` | The save is rejected and the previous values are kept. |
| 12 | Wait for an occurrence inside the window | Nothing is sent and the occurrence is not lost; history shows no row yet. |
| 13 | After the window closes | The reminder is delivered exactly once. One history row, no duplicate. |
| 14 | Inspect the database | One `user_notification_preferences` row, with the zone stored as entered or canonicalised. |
| 15 | Sign in as a second user | `Your preferences` shows the defaults, not the first user's settings. |

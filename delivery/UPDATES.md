# Shell updates and push

Complete shells own discovery, verified download/staging, retries, persisted preferences,
and Android JobScheduler work in the private `:paravoid_updates` process. The payload
application is not started to perform background work. Activation remains a cold-start
operation. Restart behavior defaults to manual and can be configured independently of installation.

## Downstream configuration

```groovy
paravoid {
    packaging = 'complete'
    updates {
        enabled = true
        baseUrl = 'https://updates.example/updates/'
        channel = 'stable'
        restartBehavior = 'manual' // manual, prompt, or automatic
        authentication = 'public' // or apkKey, with an installed signed grant
        trustPolicyFile = layout.projectDirectory.file('trust-policy.json')
        schedule {
            intervalSeconds = 21600
            flexSeconds = 3600
            checks = true
            downloads = true
            checkUnmetered = false
            downloadUnmetered = true
            charging = false
            batteryNotLow = false
            deviceIdle = false
        }
        push {
            enabled = true // defaults to false
            webSocketUrl = 'wss://updates.example/updates/v1/events'
            behavior = 'prompt' // default when push enabled; alternative: automatic
        }
    }
}
```

By default the WebSocket stays connected while an app Activity is visible, reconnects
with capped backoff, and closes when the app leaves the foreground. Opt-in background
connections use the foreground service described below. Background notification delivery
can also use a downstream adapter. Android controls
when scheduled jobs run; intervals and push hints are not exact execution deadlines.

## Background connection for development

For a phone receiving pushed development builds, enable a shell-owned foreground service:

```groovy
updates {
    // ... enabled, baseUrl and trust configuration ...
    restartBehavior = 'automatic'
    schedule {
        checks = true
        downloads = true
        downloadUnmetered = false // allow development updates over mobile data too
    }
    push {
        enabled = true
        webSocketUrl = 'wss://updates.example/updates/v1/events'
        backgroundConnection = true // default false; requires a WebSocket URL
        behavior = 'automatic'
    }
}
```

`backgroundConnection = true` makes the feature available in the shell. The **App
updates** screen includes **Keep update connection in background**, which defaults to
**off** even in a shell configured with this capability. Turning it on starts the
service in `:paravoid_updates`; later app launches resume it while the preference is on.
The same authenticated
WebSocket remains connected when Activities stop, with reconnect backoff and normal
signed discovery/download verification. No payload Application is loaded in this process.
The ongoing **App update connection active** notification opens the app and includes
**Stop background connection**. Turning the screen toggle off or using the notification
Stop action disables the same persisted preference across app launches and payload updates.
Turn the screen toggle on to resume, or use `ParavoidPush.startBackground(activity)`
from a visible Activity.
`ParavoidPush.stopBackground(context)` provides the same pause control to app-owned UI.
The start method returns whether a service start was requested, not connection success.
Ordinary visible-app push and scheduled polling remain available while background push
is paused. The app owns notification permission requests.

Background updates can download and stage; automatic restart still waits for a resumed
app Activity. Keep the app visible to get the full push/download/restart development loop.
Push checks retain their one-minute rate limit and network/user preferences still apply.
The server/store must send the event hints documented below after publishing.

The generated service declares Android's `specialUse` foreground service type and its
subtype explanation. A continuous event subscription has no finite data-sync task;
`dataSync` services on Android 15 with target SDK 35+ have a six-hour daily timeout.
Google Play reviews `specialUse` declarations; downstream distributors must evaluate
this use case before enabling it in production. See
[Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).
This is opt-in configuration, so enable it only in your development shell configuration
if production does not need it. Android/user termination, force-stop and device power
management can still interrupt connections. The service does not acquire a wake lock,
start at boot, or automatically respawn after process death; reopening the app starts it
again unless paused. This also lets coordinated app restart stop the update owner safely.

Push and polling share the same scheduler and verification path. Automatic work respects
check/download preferences, network constraints and custom policy. Push hints coalesce
and duplicate event IDs are persisted (latest 128); push checks are limited to one per
minute. A hint never authorizes an archive or supplies an executable URL.

`prompt` applies to automatically discovered offers, including scheduled checks. The
shell asks before download, through a foreground dialog or a notification. The app owns
requesting Android notification permission; an unavailable permission leaves the offer
available through foreground UI and `ParavoidUpdates`. “Not now” suppresses the same
release until a different offer is discovered. `automatic` stages without prompting.
Explicit `updateNow` is an app/user command and may override automatic preferences.

## Restart policy

`updates.restartBehavior` is independent of `push.behavior` and also applies to polling
and explicit downloads:

- `manual` (default): stage the update and let the app/user choose when to restart.
- `prompt`: once an update is staged and an app Activity is visible, show the shell's
  restart confirmation. “Not now” suppresses that offer's automatic prompt.
- `automatic`: once staged and an app Activity is visible, restart without confirmation.
  This stops ongoing app work and can discard unsaved changes.

The shell's **App updates** controls include an **Auto restart app** toggle. It defaults
to on when `restartBehavior` is `automatic` and off otherwise. Turning it on selects
automatic restart for this installation; turning it off restores `manual` or `prompt`
behavior, except that an installed `automatic` default becomes `manual`. The choice is
stored in shell-owned app data and survives app restarts and payload updates.

Background completion waits for a visible app Activity; the shell does not attempt a
background Activity launch. A normal cold start can activate the pending update directly.
Policy-driven prompts/attempts are remembered per archive to avoid repeated prompts or
restart loops if activation fails. Explicit restart remains available to retry.

For entirely app-owned behavior, use `manual`, observe pending updates, and call:

```java
ParavoidUpdates.get().restart(activity);       // app owns confirmation/timing
ParavoidUpdates.get().restart(activity, true); // shell asks for confirmation
```

The return value means the restart screen was opened, not that restart completed. The
shell coordinator runs separately from payload processes, stops them, and relaunches the
app. If it cannot establish safe process ownership or stop them, it reports failure.

## Commands and custom providers

- `ParavoidUpdates.get().checkNow()` discovers only.
- `updateNow()` freshly checks and downloads; `updateNow(state.available)` targets that
  exact verified offer. A changed offer is reported as `OFFER_CHANGED` and requires a
  new command/approval.
- `observe(...)` exposes phase, progress, available/pending release, `promptRequired`,
  schedule and errors. `dismiss(release)` declines that offer.
- `schedule(UpdateSchedule)` changes persisted scheduling preferences;
  `preferences(checks, downloads, unmeteredOnly)` is the simpler existing interface.

Optional `checkerClass`, `updaterClass`, and `policyClass` implement `UpdateChecker`,
`UpdateUpdater`, and `UpdatePolicy` from `paravoid-update-api`. Supply their public
no-argument implementations in code-only JARs through `paravoidUpdateImplementation`.
They must work without payload initialization or resources. Checker/updater replacement
only changes transport: shell signatures, identity, expiry and lifecycle admission still
apply. The checker returns signed metadata bytes; the updater writes the complete archive
to the provided bounded output stream and cooperates with cancellation. Default HTTP
supports resumable partials. Custom providers may manage their own private resume cache.

## Static hosting and metadata renewal

Set `mode = 'feed'`, `metadataUrl = 'https://.../feed.json'`, and
`payloadUrlTemplate = 'https://.../{releaseId}.vpk'` to use ordinary static files.
The signed portable feed lists releases with `minSdk`, `maxSdk` (0 means unbounded),
and `abis` (empty means any). The shell picks the highest compatible payload version.
Its signature domain is `paravoid/v1/feed\n`; API heads retain `paravoid/v1/head\n`.
Feeds use the installed head trust keys and the existing 24-hour maximum validity.

The reference server's `publishers` catalog entries specify `applicationId`,
`shellContractId`, `channel`, `keyId`, `privateKeyFile` (PKCS#8 DER RSA-3072), and
`releases` containing the signed release identity, compatibility and `file`.
`stateDirectory` stores durable revisions. The server renews signed announcements when
less than six hours remain, before evaluating conditional HTTP requests. VPK bytes and
payload version stay unchanged. Python `cryptography` is needed for signing.
`python3 delivery/reference/server.py --catalog catalog.json --export output/` exports
feeds for static hosting; rerun and publish them before expiry. Expired cached metadata
requires a fresh check, including when the chosen VPK has not changed.

## Provider-independent notification delivery

A downstream app may configure both channels or only one:

```groovy
updates {
    // ... enabled, trust and delivery configuration ...
    push {
        enabled = true
        behavior = 'automatic'
        transportClass = 'example.push.Registration' // optional PushTransport
        authenticationClass = 'example.push.Authentication' // optional PushAuthentication
        componentClasses = ['example.push.NotificationService']
    }
}
dependencies {
    paravoidUpdateImplementation project(':shell-push-adapter')
}
```

Declare the adapter service/receiver in the ordinary app manifest. `componentClasses`
routes these shell-packaged classes to the update process; the build checks they are
present in `paravoidUpdateImplementation`. Keep vendor permissions/export rules in that
manifest. The adapter owns provider registration, authentication and extracting payloads.
It calls `ParavoidPush.receive(context, eventBytes)` from any app process; the method
validates scope and durably schedules a hint job. A true return means queued, not installed.
No Firebase, UnifiedPush, store SDK or mandatory notification provider is included.

`PushTransport.run(request, listener, cancellation)` runs off the UI thread. It can
register a provider and wait for events; use `request.privateDirectory` for idempotent
registration state and honor cancellation. OS-created delivery components should call
`ParavoidPush.receive` directly, so they work after process death. These adapters must
be code-only shell dependencies; compile against runtime/API types without bundling a
second runtime. SDKs requiring AAR resources or payload initialization need a suitable
shell adapter rather than being added blindly to this configuration.

`PushAuthentication.headers(request)` optionally supplies connection headers. By default,
the built-in WebSocket sends the installed bearer only to the configured delivery audience
(same HTTPS/WSS host and path prefix). Redirects are not followed. Credentials never go
in event bodies, query strings or logs.

## Store-neutral wire protocol

WebSocket subprotocol: `paravoid.updates.v1`. The shell sends one UTF-8 JSON subscription:

```json
{"version":1,"type":"subscribe","applicationId":"example.app","shellContractId":"<64 lowercase hex>","channel":"stable"}
```

The server validates credentials and scope, then sends UTF-8 JSON hints, at most 4096 bytes:

```json
{"version":1,"type":"updates_changed","applicationId":"example.app","shellContractId":"<64 lowercase hex>","channel":"stable","eventId":"unique-event-id"}
```

Use the identical hint bytes in notification payloads. Event IDs use the protocol's
identifier syntax. Send a hint after successful subscription/reconnection as well as
publication/withdrawal; this repairs missed events through a normal signed check.
Implement ping/pong, bounded messages and permission revocation. No archive, URL,
credential, release version or signature is accepted from the hint itself.

A store adapter implements this protocol independently of Paravoid. A notification bridge
may subscribe to the same authenticated stream and forward hints using its chosen provider;
registration/addressing/retry guarantees of that provider belong to the integration.

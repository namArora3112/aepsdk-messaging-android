# Connected Android telemetry validation

This is an unreleased, paired-source implementation. Do not publish an instrumented
extension against an older Core. The normal sample does not register telemetry or
depend on the optional artifact.

## Architecture

```text
Instrumented public SDK entry
    -> optional Core recorder, fixed metadata only
    -> bounded memory handoff
    -> dedicated collector worker
    -> SQLite contexts, entries and immutable batches
    -> internal request/outcome event
    -> isolated Edge transport, one network attempt
    -> acknowledgement or Core-owned retry
```

Core collects the host application, OS, device, permissions and registered SDK
inventory centrally. Individual extensions must not duplicate permission/device
collection. Their hooks supply only fixed extension/version/API metadata.

The asynchronous handoff is best effort until SQLite commits. It is not a
synchronous durability promise for every API invocation.

## Canonical reporting fields

Source: [Proposed CJA Dimensions and Metrics](https://wiki.corp.adobe.com/pages/viewpage.action?pageId=4119775495).

| Dimension | Client source |
|---|---|
| SDK used, API name, API event type | `telemetry.events[].extension`, `apiName`, `apiEventType` |
| Failure code and name | `errorCode`, `errorName`, only for an observed eligible failure |
| Customer org | `orgId` from configuration |
| Installation | Random no-backup `metadata.uuid`, not ECID or a device identifier |
| SDK adoption/version | Actual registered `sdkVersions[].name` and `.version` |
| Platform and OS | `metadata.platform`, `.osVersion` |
| Application | `metadata.application.package`, `.name`, `.version` |
| Device | `metadata.device.manufacturer`, `.model` |
| Permissions | `metadata.permissions[].name`, `.granted` |
| Startup/upgrade milestones | `TIMELINE` records, `appStart` / `adoptionUpgrade` |

Use the full-payload variant: usage repeats the immutable context that applied
when records were collected. Old queued usage must not acquire the new app/SDK
versions after an upgrade. Usage reports group by each entry's extension, not by
every item in `sdkVersions`.

Friendly SDK names, distinct installs/orgs/apps, rates and calculated metrics are
CJA data-view/report definitions, not additional client counters.

Wrapper metadata, locale, React Native architecture and host build settings are
explicitly not in the current schema. No API key, arguments, URLs supplied by a
customer, notification tokens or Edge identifiers are added.

## Flush and retry contract

Usage becomes ready by accumulated pending byte size, not elapsed time, activity
transitions or process termination. The validation app selects a 1,024-byte target.
Once a delivery pass is triggered, records are grouped by their saved context;
the last context-specific envelope may be below the target.

Persist entries first. Atomically assign them to an immutable batch before sending.
New calls belong to later pending entries; acknowledging one batch must not reset
or remove calls made during that upload.

The Edge adapter accepts at most 50 usage entries and 65,536 UTF-8 bytes for the
**complete wire envelope**, including `events` and the empty `identityMap`.
It does not insert a second copy into Edge's business queue.

| Result | Collector action |
|---|---|
| Valid successful Edge response | Acknowledge the saved batch; this is not final dataset ingestion proof |
| Network failure, 408, 425, 429, 5xx | Retry the same saved event ID with bounded policy/backoff |
| Permanent HTTP rejection such as 401/403 | Do not retry forever; quarantine/drop according to outbox policy |
| Missing destination, disallowed policy, busy adapter or pending business queue | Defer without claiming a network attempt |

Positive integer `Retry-After` seconds are returned to the collector, bounded at
24 hours. HTTP-date headers are not parsed. The adapter uses five-second connect
and read timeouts and a 15-second attempt watchdog.

Response identity/state/location-hint handles are not applied. Requests have an
empty identity map and no customer state metadata. The stable XDM event ID permits
correlation; server-side deduplication is not assumed.

No background scheduler or WorkManager delivery guarantee is claimed. Committed
work survives a kill and can resume when the SDK next runs with permission.
Below-threshold usage is not forced out merely because the app closes.

### Current safety defaults, not production SLOs

The collector has 256 queued records and a worker drain of at most 256 records.
An abrupt kill can lose either group before its SQLite transaction commits.
Overflow drops new admissions rather than blocking the calling API.

The outbox permits 5,000 entries, 128 contexts, a 5 MiB logical payload/template
budget and seven-day retention. These are not hard limits on SQLite's physical
database, journal, heap or total network traffic. Delivery allows one in-flight
batch, six non-blocked attempt starts per rolling minute and ten attempts per
batch. Backoff starts around ten seconds, grows to approximately an hour, and
adds up to 20 percent jitter. Blocked outcomes undo provisional attempt accounting.
The 64 KiB wire limit bounds request bodies to at most 384 KiB per minute at that
quota; headers, responses and connection overhead are additional.

Permanent adoption rejection quarantines that context; its dependent usage is
not sent as if adoption had succeeded. New contexts remain independent.
Server-side deduplication, eviction preference, recovery from quarantine and
release resource budgets need explicit architecture decisions.

## Wire payload examples

These are illustrative, redacted versions of the observed wire structure, not
copies of an installation's identifiers. Permissions and SDK inventory depend
on the host application; permission values follow the existing string schema.
Configuration/EventHub internals are not separate adopted SDKs.

Adoption:

```json
{
  "xdm": {"identityMap": {}},
  "events": [{
    "xdm": {
      "_id": "00000000-0000-4000-8000-000000000001",
      "eventType": "sdk.adoption",
      "timestamp": "2026-11-12T12:00:00.000Z",
      "_cjmstage": {
        "orgId": "EXAMPLE@AdobeOrg",
        "metadata": {
          "uuid": "00000000-0000-4000-8000-000000000002",
          "platform": "android",
          "osVersion": "14",
          "application": {
            "package": "com.example.app",
            "name": "Example App",
            "version": "1.0.0"
          },
          "device": {"manufacturer": "Google", "model": "sdk_gphone64_arm64"},
          "permissions": [
            {"name": "android.permission.INTERNET", "granted": "true"},
            {"name": "android.permission.POST_NOTIFICATIONS", "granted": "false"}
          ]
        },
        "sdkVersions": [
          {"name": "com.adobe.assurance", "version": "3.0.8"},
          {"name": "com.adobe.edge", "version": "3.2.0"},
          {"name": "com.adobe.edge.consent", "version": "3.0.3"},
          {"name": "com.adobe.edge.identity", "version": "3.2.0"},
          {"name": "com.adobe.messaging", "version": "3.13.0"},
          {"name": "com.adobe.module.core", "version": "3.11.0"},
          {"name": "com.adobe.module.lifecycle", "version": "3.0.1"}
        ]
      }
    }
  }]
}
```

Usage repeats the complete saved context, even when sent after an app upgrade.
This short example has one startup milestone and one API invocation; actual
batch membership is determined by the byte trigger and envelope limits.

```json
{
  "xdm": {"identityMap": {}},
  "events": [{
    "xdm": {
      "_id": "00000000-0000-4000-8000-000000000003",
      "eventType": "sdk.usage",
      "timestamp": "2026-11-12T12:00:10.000Z",
      "_cjmstage": {
        "orgId": "EXAMPLE@AdobeOrg",
        "metadata": {
          "uuid": "00000000-0000-4000-8000-000000000002",
          "platform": "android",
          "osVersion": "14",
          "application": {
            "package": "com.example.app",
            "name": "Example App",
            "version": "1.0.0"
          },
          "device": {"manufacturer": "Google", "model": "sdk_gphone64_arm64"},
          "permissions": [
            {"name": "android.permission.INTERNET", "granted": "true"},
            {"name": "android.permission.POST_NOTIFICATIONS", "granted": "false"}
          ]
        },
        "sdkVersions": [
          {"name": "com.adobe.assurance", "version": "3.0.8"},
          {"name": "com.adobe.edge", "version": "3.2.0"},
          {"name": "com.adobe.edge.consent", "version": "3.0.3"},
          {"name": "com.adobe.edge.identity", "version": "3.2.0"},
          {"name": "com.adobe.messaging", "version": "3.13.0"},
          {"name": "com.adobe.module.core", "version": "3.11.0"},
          {"name": "com.adobe.module.lifecycle", "version": "3.0.1"}
        ],
        "telemetry": {
          "events": [
            {
              "apiEventType": "TIMELINE",
              "timestamp": 1794484800000,
              "extension": "com.adobe.module.core",
              "apiName": "appStart"
            },
            {
              "apiEventType": "API_CALL",
              "timestamp": 1794484801000,
              "extension": "com.adobe.messaging",
              "apiName": "refreshInAppMessages"
            }
          ]
        }
      }
    }
  }]
}
```

The event `_id`, occurrence timestamps and payload remain identical on retry.
There is no new wire `batchId`, `schema_version`, send-time or secret API key.
SDK versions above identify the unreleased paired-source test builds, not a
claim that the corresponding published versions contain this implementation.

## Opt-in validation application

The original POC staging environment is used only when `telemetryValidation=true`:

```text
staging/1b50a869c4a2/bfcaae461edd/launch-e9937845c996
```

Its package is separate from the normal sample and earlier validation app:

```text
com.adobe.marketing.mobile.messagingsample.telemetry.connected
```

The sandbox destination and affirmative consent override live in the opt-in app
source set, not in the SDK transport. Production registration does not grant
consent. Configure `sdk.telemetry.enabled`, `sdk.telemetry.edge.configId` and
`sdk.telemetry.edge.endpoint` explicitly.

From the Messaging `code` directory, with `CORE` and `EDGE` pointing to the matching
worktrees' `code` directories:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew --console=plain :testapp:assembleDebug \
  -PlocalCorePath="$CORE" -PlocalEdgePath="$EDGE" \
  -PtelemetryValidation=true -PtelemetryAppVersion=1.0.0
```

Use the SDK root build, not `-p testapp`: that directory also contains an unrelated
standalone AGP consumer build.

Install only this APK; do not clear or replace the normal sample's application data.
The debug-only `TelemetryValidationActivity` accepts `status`, `generate` (count
capped at 200), `offline`, `online`, `pause`, `resume`, `deny`, `allow`, `configure`
and `business`. Generation calls the real public Messaging refresh API.
Consent controls send the required `{"consents":{"collect":{"val":"n"}}}` (or
`"y"`) root structure. A bare `collect` map is ignored by the Consent extension
and is not a valid opt-out test.

Request captures and local counters stay in the validation app's no-backup
`telemetry-validation/capture.jsonl`. Offline simulation intercepts only telemetry,
not business traffic. Its flag survives a process restart for recovery checks.
Do not treat a captured request or HTTP status alone as ingestion evidence.

## Review boundaries

The original POC's failure attribution uses the failed Edge XDM event type as
`apiName`, with extension `com.adobe.edge`. This is not a correlation to the
originating Messaging/public API. CJA's proposed failure ratio is an event ratio,
not a per-invocation success probability.

Public entry recording does not universally distinguish a customer invocation from
an SDK-internal call into the same public facade. Known delegation paths are kept
single-counted; unresolved origin/coverage cases must be listed rather than called
complete. In particular, Messaging's version accessor also serves internal version
lookup and is not instrumented as customer usage.

Physical-device kill/background/stress tests, reliable OS background execution,
metered/low-power policy, physical disk budgets, server deduplication and additional
schema/version fields need explicit senior review and evidence. They are not
silently enabled by this implementation.

## Executed verification and open review

The final focused suites passed: Core/Identity/telemetry 156 tests, Edge 42,
Messaging 61 and twelve additional SDKs 193. Formatting passed. Assurance has 47
pre-existing style violations outside changed files; Audience and Consent have
no checkstyle task. Core API compatibility and normal sample compilation passed.

Emulator checks verified saved-ID recovery through offline/process restart,
successful reconnect, consent and configuration opt-out with twenty calls each,
and an app update from 1.0.0 to 1.1.0 without data clearing. Old pending usage kept
version 1.0.0; new usage carried version 1.1.0, twenty refresh entries and an
adoptionUpgrade milestone. The installation UUID remained unchanged. Accepted
outcomes cleared the outbox; these are not final dataset-ingestion assertions.

The twelve additional SDKs have 65 hook sites covering 78 inventoried operational
signatures. LiveUpdates and CampaignClassic's two automatic notification paths
remain deferred; metadata accessors and SDK plumbing are intentionally excluded.
This is not blanket coverage of every public API.

The source-backed checklist, eight explicit flow answers and 24 senior questions
are published at [Implementation checklist and senior review](https://wiki.corp.adobe.com/pages/viewpage.action?pageId=4121252223).
It also records unsupported namespace rotation, permission-snapshot freshness,
physical corruption/full-disk recovery and durable opt-out during storage failure.

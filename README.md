# DSH Remote Android · Gates 0C/0D/0E

This is the first real Android consumer of the Host-plugin-owned experimental
[`v1alpha` protocol](../host-workspace/deepseek-harness/packages/host/remote/protocol/v1alpha/dsh_remote_v1alpha.proto). It is a
native Kotlin/Compose engineering app, not yet the production mobile shell.

The v2 presentation layer (`ui/v2/`, design:
[`ANDROID_V2_PRESENTATION`](../docs/engineering/ANDROID_V2_PRESENTATION.md)) is
now the default paired surface: a four-tab IA (会话/审批/产出/通知) with a Now
Running bar, a 对话/轨迹 session view, capability/lease-gated composer and a
client-derived notification feed, rebuilt after the full-vision prototype
([mapping](../docs/product/PROTOTYPE_V2_FACT_MAPPING.md)). Every element maps to
a real Host fact or carries an honest not-connected placeholder; the carrier,
stores and acceptance spine are untouched. The legacy Gate 0D/0E diagnostics
screens remain compiled for existing fixture/instrumented evidence until slice
D retires them. v2 visual audit: `renderer_fixture=v2-home|v2-chat|v2-approvals|v2-offline`
(shots under `artifacts/ux/prototype-v2/android-v2-*.png`).

It proves generated protobuf-lite messages, a grpc-java OkHttp HTTP/2
bidirectional stream, a Host-supplied real multi-Session directory, snapshot/cursor
ACK, duplicate suppression, gap refusal, same-Host retained-suffix resume with
explicit acceptance, fresh-snapshot fallback,
typed command outcomes, Host-granted capability capture, lifecycle teardown and visible preservation of generic,
terminal, diff and unsupported Host presentation kinds on Android. The Gate 0D
surface adds privacy-minimized workspace/activity facts, running-first Session
selection, stable assistant identity, in-place streamed response updates, typed
native timeline rows, bounded tool-evidence details with preserved return
position and an isolated 720-row Macrobenchmark. A dedicated
logcat tag records only connection epochs, monotonic elapsed time, projected row
count, cursor and failure class; it never records Session/Host/stream identifiers
or projected content.

## Security boundary

The debug manifest deliberately permits a plaintext carrier only for the standalone
semantic fixture, which binds to `127.0.0.1` and is reached through `adb reverse`.
The source-backed Host rejects that legacy plaintext method with `UNAUTHENTICATED`;
its real application path exposes Session data only through paired Noise IK
`SecureConnect`. The source-backed Host may now opt into one explicit private-interface
LAN listener; this engineering path remains default-off and never exposes the Web
administrator outside loopback.

Gate 0E now adds the shared Rust Noise core through direct JNI and stores the
Android static identity only as an AES-256-GCM Keystore-wrapped envelope under
`noBackupFilesDir`; StrongBox is requested when available. The app now completes
XXpsk3 pairing with explicit Host confirmation, persists the authenticated Host pin,
opens Noise IK `SecureConnect`, and reconciles lost final pairing receipts without
promoting an unproved pin. Windows Host persistence and the shipped Host-local Web
confirmation/revocation surface form the corresponding authority path.

Physical process-reclaim passes on the vivo X90: an already paired process reached
`Ready`, Android `force-stop` removed its PID and set the package stopped bit, and a
new Launcher process reached `Reconciled` plus the same Loader-composed cold DSH
history without scanning another invitation. See the
[physical record](../docs/reviews/gate-0e/PAIRING_PROCESS_RECLAIM_PHYSICAL_ACCEPTANCE.md).

The closed Gate 0E write profiles are additive rather than a relaxation of the
default. Pairing accepts one closed Host-selected profile: read-only (`3`),
approval reviewer (`19`), Session control (`71`), Session operator (`79`) or
Session supervisor (`95`); ordinary invitations remain read-only. A controller
may acquire a Host-owned Session lease and send only after the
authenticated `ServerHello` grant exactly matches the locally confirmed profile.
Session operator additionally enables exact-turn Stop. Approval uses its separate
capability (`16`) and the exact live interaction revision, never the control lease.

Before a Send, Stop or approval-decision frame is written, Android atomically
stores the command ID, exact request semantics and required authority/fence in a
separate AES-256-GCM Keystore envelope
under `noBackupFilesDir`. `RECEIVED` and `UNKNOWN` retain that same identity;
reconnect/reconcile resends the same command rather than minting a replacement.
Only `COMMITTED` or definitive `REJECTED` clears the pending record, and unreadable
or uncleared protected state blocks later Send until explicit re-pairing. This
store is intentionally separate from the replaceable offline projection cache.

## Gate 0E native core

NDK r29 and `cargo-ndk` build the same source for the vivo `arm64-v8a` target and
the x86_64 emulator. From the workspace root:

```powershell
$env:ANDROID_NDK_HOME = "$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865"
Push-Location security-core
cargo ndk --target arm64-v8a --target x86_64 --platform 28 `
  --output-dir target/android-jni build --release --features android-jni
Pop-Location
```

Copy the two generated `libdsh_remote_security_core.so` files into matching
`app/src/main/jniLibs/<abi>/` directories before assembling. The API 37
instrumentation runs only on the explicitly selected emulator:

```powershell
$env:ANDROID_SERIAL = "emulator-5554"
./gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=dev.dshremote.security.SecurityCoreInstrumentedTest"
```

## Build and run on the authorized USB device

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
$deviceSerial = '<explicit-authorized-device-serial>'
adb -s $deviceSerial reverse tcp:50051 tcp:50051
adb -s $deviceSerial install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $deviceSerial logcat -v epoch -s DSHRemoteGate0C:I '*:S'
```

For representative LAN acceptance, do not configure ADB reverse. Follow
[`experiments/gate-0c-real-host`](../experiments/gate-0c-real-host/README.md), verify the
Host listener is bound to exactly one private address, and retain the Host-local QR and
comparison ceremony after discovery selection.

The following read-path behavior remains executable against the standalone
plaintext fixture. The app initially synchronizes the first running/recent Session while presenting
the complete nonblank directory. Selecting another row replaces the active
subscription with that Session's real live/cold snapshot on the same connection.
The Host sends only a workspace label, never the full `cwd`; Host-owned live question
and approval directories supply exact content-free pending counts, so Android
prioritizes input-waiting, then approval-waiting Sessions ahead of running/recent
work. Question text, choices and response authority do not cross. A read-only grant exposes **Verify lock**, whose probe must display
`REJECTED` without a DSH effect. A Session-control grant instead exposes
lease acquisition, Send and same-ID reconciliation. Tool rows show the semantic Host kind as
`TOOL · GENERIC`, `TOOL · TERMINAL`, `TOOL · DIFF` or `TOOL · UNSUPPORTED` and
retain only the bounded tool name/summary in this diagnostics surface.

The physical vivo X90 input-attention acceptance is recorded in
[`INPUT_ATTENTION_FOUNDATION.md`](../docs/reviews/gate-0e/INPUT_ATTENTION_FOUNDATION.md).
The real Loader runner's optional `attention` mode creates one live input wait, one
ordinary running Session and one cold Session; stdin `RESOLVE_INPUT` closes the wait.
Killing ADB while the Host remains live proves the encrypted directory becomes
`Offline`/`STALE` while retaining `Input waiting` only as snapshot context.

## Renderer and Macrobenchmark

The `benchmark` build is non-debuggable, R8-shrunk and profileable. Its derived
long-session fixture is enabled only in debug/benchmark builds; release ignores
the intent extra.

```powershell
$env:ANDROID_SERIAL = "emulator-5554"
./gradlew.bat :macrobenchmark:connectedBenchmarkAndroidTest
```

The emulator suppression proves only benchmark wiring. Gate 0D performance must
be accepted from the same test on representative physical hardware.

Session-directory visual evidence is captured from the debug-only source-shaped
fixture on API 37:

- [light theme](../docs/reviews/gate-0d/session-directory-api37-light.png)
- [dark theme](../docs/reviews/gate-0d/session-directory-api37-dark.png)
- [input/approval/running attention hierarchy](../artifacts/ux/dsh-input-attention-api37.png)
- [bounded terminal detail](../docs/reviews/gate-0d/tool-detail-api37-light.png)

For the separate deterministic gap fixture, in another terminal from
`experiments/gate-0c-transport`:

```powershell
pnpm device-host:gap
```

Start `dev.dshremote.gate0c/.MainActivity`. With the fixture, the expected
terminal state is `Reconciled` at cursor 41 after the intentional 41 → 43 gap.
Old committed/replayed screenshots from that standalone fixture are not evidence
of the authenticated command path. The real Host now owns the capability grant,
control lease, command journal and durable Session correlation; Android owns the
encrypted pending-command state and honest settlement UI. API 37 now also completes
one direct Android → Loader-composed Host command and observes `COMMITTED` plus one
projected user input. A second API 37 run closes after `RECEIVED`, loses the terminal,
restarts the real Loader composition and reconciles the protected same command ID as
replayed `COMMITTED` with one durable Inbox insertion. The vivo X90 subsequently
passed OS-process reconstruction with the same protected command ID, and a physical
vivo/API-37 two-device run proved held-by-other, lease expiry, one successor commit
and revoked-device reconnect denial with no Host or Session metadata. See the
[physical command acceptance record](../docs/reviews/gate-0e/COMMAND_PHYSICAL_ACCEPTANCE.md).
The same real stack also proves Session-operator Pair → Acquire → Send-to-running →
exact-turn Stop `REQUESTED` → non-replayed `STOPPED`. The representative vivo X90
now passes that same secure Loader-composed path, same-ID recovery after process death,
a two-second pre-effect lease-expiry refusal and foreground Host-local revoke with no
Stop effect. The app sends an authenticated heartbeat after five idle seconds and
requires its exact acknowledgement within five more; timeout preserves cached state
as offline rather than presenting the revoked stream as current. Background/Doze and
lease transfer remain physical hardening gates. See the
[Stop acceptance record](../docs/reviews/gate-0e/STOP_ACCEPTANCE.md).

Approval owner layers are also implemented: real Loader tests settle exact
ApprovalService decisions through durable `approval/decided` facts; the carrier
requires the Host-granted capability and current revision; Android protects the
same decision ID for recovery and presents progressively stronger confirmation.
API 37 now completes one continuous least-privilege profile `19` Pair → destructive
approval → exact-revision `COMMITTED` path and the real protected create-new operation
executes once. A second run closes after `RECEIVED`, restarts the real Loader and
reconciles the same protected ID as replayed `COMMITTED` with no second effect.
The representative vivo X90 now passes the same profile-19 destructive approval
main path with the protected create-new effect exactly once. It also passes Android
process death after `RECEIVED` with same-ID replayed `COMMITTED`, plus foreground
Host-local revoke while the approval remains pending with zero protected effect.
The production `tool-fs/write` and `edit` owners now supply bounded evidence only after
independent policy asks, and a real Loader test proves the write path and cold repair.
API 37 automation plus physical vivo inspection cover the destructive interaction at
200% font. Physical Google TalkBack traversal additionally proves the source-shaped
Compose evidence, one-time actions, merged acknowledgement and gated final action are
reachable in order. Revoked or changed authority now enters an explicit
`NEW PAIRING REQUIRED` boundary instead of ordinary reconnect. **Pair again** names and
acknowledges removal of the old Keystore identity, Host pin, offline projection/drafts,
control lease and protected pending command before returning to `UNPAIRED`; API 37 proves
those stores are cleared and a different public key is minted. The final APK was installed
on the vivo X90 and the pre-reset boundary plus disabled confirmation were visually checked
without deleting the current identity. The continuous physical ceremony now passes as
one uninterrupted Host run: revoke surfaced the boundary on the real carrier, the
acknowledged reset executed, a new invitation/comparison/IK ceremony minted a different
device identity, and the old approval command ID replayed under that replacement
identity was rejected with `command-id-reused` and zero protected effect
([record](../docs/reviews/gate-0e/REPAIR_CEREMONY_PHYSICAL_ACCEPTANCE.md)).
Carrier-driven arrival/settlement announcements remain the open product gate. See the
[approval acceptance record](../docs/reviews/gate-0e/APPROVAL_ACCEPTANCE.md).

LAN discovery is now a user-initiated, privacy-minimized flow. API 37 uses Android's
system NSD picker; Android 13–16 requests Nearby Devices only when required. The app
accepts only the versioned `_dsh-remote._tcp` identity allowlist, compares paired
state from its own protected Host pin and shows no Session metadata after selection.
Permission failure, multicast timeout and resolution failure retain the QR/manual
invitation path. [API 37 discovery visual](../artifacts/ux/dsh-lan-discovery-api37.png)
and [system NSD picker](../artifacts/ux/dsh-lan-system-picker-api37.png) are UI/privacy
evidence. The physical vivo X90 path now proves Android 16 discovery, denial and
timeout/manual recovery plus direct Wi-Fi Pair→Noise→READY with no ADB reverse; API 37
also proves real system-picker cancellation. See the
[physical acceptance record](../docs/reviews/gate-0e/LAN_DISCOVERY_PHYSICAL_ACCEPTANCE.md).

Debug-only API 37 visual states (source-shaped fixture, not Host E2E proof):

- [Session-control Send](../artifacts/ux/android-command-settlement/session-control.png)
- [Unknown outcome reconciliation](../artifacts/ux/android-command-settlement/command-unknown.png)
- [Exact-turn Stop](../artifacts/ux/dsh-stop-ux-api37.png)
- [Stop requested / quiescence](../artifacts/ux/dsh-stop-requested-api37.png)
- [Sensitive approval](../artifacts/ux/dsh-approval-sensitive-api37.png)
- [Destructive approval confirmation](../artifacts/ux/dsh-approval-destructive-confirm-api37.png)
- [Destructive approval at 1.5× font](../artifacts/ux/dsh-approval-destructive-large-font-api37.png)
- [Vivo 200% approval attention](../artifacts/ux/dsh-approval-large-attention-vivo.png)
- [Vivo 200% approval actions](../artifacts/ux/dsh-approval-large-actions-vivo.png)
- [Vivo 200% bounded destructive dialog](../artifacts/ux/dsh-approval-large-dialog-bounded-vivo.png)
- [Vivo new-pairing-required boundary](../artifacts/ux/dsh-new-pairing-required-final-vivo.png)
- [Vivo identity-reset confirmation (disabled)](../artifacts/ux/dsh-new-pairing-confirm-final-vivo.png)

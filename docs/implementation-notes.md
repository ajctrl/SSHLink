# SSHLink implementation notes — v0.3.8

This document consolidates the implementation overview, specification-compliance
notes, review fixes, and validation record for the v0.3.8 implementation of
[SSHLink specification](ssh-tunnel-spec.md).

## Implementation overview

- SSH public-key authentication only; SSH `none` and password authentication are disabled.
- Ed25519 keys are generated in the app and stored as OpenSSH private key v1 files.
- The public key can be displayed and copied. A missing or stale `.pub` file is rebuilt from the validated private key.
- Multiple named or unnamed `127.0.0.1:localPort -> remoteHost:remotePort` local forwards are supported.
- `ServerAliveInterval` and `ServerAliveCountMax` are configurable.
- Only positively identified transient network failures trigger exponential-backoff reconnection.
- Authentication failures, Host Key changes, invalid keys, bind failures, and unclassified failures stop automatically as terminal errors.
- Android default-network changes invalidate the current connection and trigger SSH reconnection and DNS resolution.
- Forward destination names are resolved by the SSH server.
- Settings are stored as JSON. Export and import exclude the private key and Host Key pins.
- Logs are bounded and retained in memory.
- GitHub Actions runs unit tests, lint, release builds, and a byte-for-byte unsigned APK reproducibility check on independent runners.
- A desired tunnel is restored after reboot or package replacement unless a terminal failure has blocked retry.

## Specification compliance

| Specification area | Implementation |
|---|---|
| SSH connection | JSch Session with Ed25519 public-key authentication only |
| Local Forward | `Session.setPortForwardingL("127.0.0.1", ...)`; ports `1024..65535` |
| Key format | Bouncy Castle and an OpenSSH private key v1 codec |
| Private-key storage | App-internal `filesDir/keys`; excluded from Android backup and device transfer |
| Multiple forwards | Persisted list; every forward is recreated after reconnect |
| Foreground operation | `TunnelService` with the `specialUse` FGS type |
| Always-on behavior | Power-policy prerequisites, a service-scoped partial wake lock, and runtime policy checks |
| Reconnection | Disconnect monitoring and retryable-only exponential backoff |
| Network changes | Default-network callback, generation invalidation, and reconnection |
| SSH DNS | Active Android `Network.getAllByName()` on each new SSH connection |
| Forward DNS | Remote hostname is passed unchanged through SSH `direct-tcpip` |
| Settings | Synchronized JSON persistence with atomic replacement; corrupt data fails closed |
| Export/import | Storage Access Framework JSON; private key and Host Key pins excluded |
| UI | Main status/forward/log view and settings/key/recovery/power-policy view |
| Privacy | No advertising, analytics SDK, or telemetry code |
| Android target | `compileSdk` and `targetSdk` 36; `minSdk` 26 |

## Implementation details beyond the specification

### Connection ownership and reconnection

`ConnectionGenerationState` owns active and in-flight SSH Sessions by generation.
A reconnect request detaches both the old active Session and any connection attempt.
An obsolete generation cannot:

- commit a first-seen Host Key;
- publish local forwards as the active Session; or
- transition the current connection to a terminal failure.

Connection operations are serialized on a single-thread scheduled executor.
Retry backoff ranges from 1 to 60 seconds. Unknown failures are terminal rather
than assumed to be transient.

### SSH authentication and key exchange

Every Session applies the following policy before `Session.connect()`:

- `StrictHostKeyChecking = yes`
- `enable_auth_none = no`
- `PreferredAuthentications = publickey`
- `enable_pubkey_auth_query = no`
- `PubkeyAcceptedAlgorithms = ssh-ed25519`
- `NumberOfPasswordPrompts = 0`

The security-sensitive authentication defaults are also set globally as defense
in depth. Public-key capability queries are disabled, so authentication proceeds
directly with a signed Ed25519 request.

The JSch default KEX list and ordering are retained except for
`diffie-hellman-group-exchange-sha256`, which `SshSessionPolicy` removes for each
Session. The application does not maintain a separate KEX allow-list.

### Host Key verification

Host Key verification uses deferred TOFU pinning. An unknown key is staged during
the handshake and persisted only after successful public-key authentication by
the current connection generation. Later key changes are rejected.

DNS names, IDNs, IPv4 addresses, and IPv6 addresses are normalized for pin
identity. IPv6 text is generated from the parsed 16-byte address in RFC 5952
form, so equivalent spellings share one pin. Ambiguous numeric IPv4 forms such as
`2130706433`, `127.1`, and leading-zero octets are rejected.
The same normalized host value is used for DNS resolution and the SSH connection,
so validation and connection behavior cannot diverge for IDNs or bracketed IPv6.

A corrupt Host Key database fails closed. Settings can back up the corrupt bytes
internally and reset all pins without removing the SSH private key or ordinary
settings.

### Android power policy and lifecycle

Before Start, the app requires:

- the app not to be in Android's Background Restricted state;
- Battery Optimization exclusion; and
- on Android 13 and later, Low Power Standby allowance when that feature is enabled.

`TunnelService` holds a `PARTIAL_WAKE_LOCK` only while running. Screen on/off and
power-save-mode signals trigger policy re-evaluation, with a five-second fallback
poll for settings and OEM changes that do not emit an accessible broadcast. If a
standard Android policy becomes incompatible with an always-on socket, the
service enters ERROR instead of silently remaining disconnected.

The service is `START_STICKY`. `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` restore
a user-requested tunnel only when durable retry blocking is absent. Android may
suppress background behavior after force-stop, under Background Restricted, or
under OEM-specific policies; those cases require device testing.

### Data and resource protection

- Private key: app-private `filesDir/keys/id_ed25519`.
- Backup: `android:allowBackup="false"`; Android 12+ data-extraction rules explicitly exclude `files/keys/` from cloud backup and device transfer.
- Corrupt `config.json`: fails closed and is not overwritten by normal Save or Export.
- Configuration recovery: validated JSON Import only.
- Import JSON limit: 1 MiB.
- Forward limit: 128 entries.
- SSH host, remote host, and username have explicit length limits.
- Local forwarding bind address is hard-coded to `127.0.0.1`.
- Local ports are limited to `1024..65535`.
- One app-managed key identifier, `default`, is implemented; the specification does not require multi-key selection.

## UI and user setup

The UI supports target SDK 36 edge-to-edge insets. Android 13+ notification
permission is requested on Start. A denied permission does not necessarily stop
the foreground service, but notification-drawer visibility and its Stop action
can be restricted.

Initial setup:

1. Configure SSH Host, port, username, and keepalive values in Settings.
2. Generate an Ed25519 key.
3. Add the displayed public key to the SSH server's `authorized_keys`.
4. Add and save the required local forwards.
5. Resolve all power-policy warnings shown in Settings.
6. Start the tunnel from the Main screen.

The application does not change DNS or connection names used by applications
that consume the tunnel.

## Build and continuous integration

Pinned build versions:

- Android Gradle Plugin 9.3.2
- Gradle 9.5.0, with distribution SHA-256 verification
- Android SDK Build Tools 36.0.0
- JSch 2.28.7
- Bouncy Castle 1.85.2
- Robolectric 4.16.1
- `compileSdk` / `targetSdk` 36
- `minSdk` 26

Dynamic and changing Gradle dependencies are rejected during resolution.
Robolectric lifecycle tests run at SDK 35 on JDK 17 because Robolectric execution
at SDK 36 requires JDK 21.

With an Android SDK and dependency-network access:

```bash
./gradlew clean testDebugUnitTest lintRelease assembleRelease
```

The unsigned APK is produced at:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

The GitHub Actions workflow uses two fresh runners:

1. Runner A performs unit tests, lint, and an unsigned release build.
2. Runner B performs an independent clean build of the same commit.
3. Runner A and B APKs are compared byte for byte.
4. The unsigned APK and SHA-256 are published as artifacts.
5. On a `v*` tag, a signed APK is produced only when all required signing secrets are available.

The signing keystore is not stored in the repository.

## Validation record

### Checks completed in the original v0.3.8 review environment

- Android manifest XML and GitHub Actions YAML parsing.
- `gradlew` shell syntax.
- Structural confirmation that local forwarding binds to `127.0.0.1`.
- Compilation of Android-independent policy and state classes with `kotlinc`.
- Equivalent IPv6 spelling and ambiguous numeric IPv4 rejection checks.
- Reconnect counter reset on a new generation.
- Background Restricted priority and fail-closed behavior.
- Ed25519 private-key generation and public-key reconstruction round trip.
- Compilation and execution of `SshSessionPolicy` against an API-shaped JSch Session stub.
- KEX filtering while preserving the ordering of remaining algorithms.

The review environment did not contain an Android SDK and could not reliably
resolve Gradle/Maven dependencies. It therefore did not run
`testDebugUnitTest`, `lintRelease`, `assembleRelease`, or Robolectric. Those
checks remain CI requirements. Full Gradle dependency checksum metadata was not
generated from a partial or untrusted dependency resolution.

### Automated test coverage noted during review

- SSH policy values and KEX filtering.
- Foreground promotion before power-policy validation.
- Battery Optimization fail-closed behavior.
- Partial wake-lock acquisition and release.
- Stop and sticky restart behavior.
- Boot restoration gates and terminal retry blocking.
- Background Restricted policy behavior.
- IPv6 Host Key identity normalization.
- Ambiguous numeric IPv4 rejection.

### Manual release gates

Test on supported Android devices or emulators:

- a server that permits SSH `none`, confirming the app still requires the Ed25519 key;
- successful public-key authentication without password or keyboard-interactive fallback;
- equivalent IPv6 host spellings retaining the same Host Key pin;
- user Start followed by reboot and automatic restoration;
- terminal authentication or Host Key failure followed by reboot and no retry;
- Wi-Fi/mobile handover during an in-flight SSH connection;
- screen-off, Doze, Low Power Standby, and Battery Optimization changes;
- Background Restricted Start and runtime failure behavior;
- process recreation after a terminal retry block;
- notification denial and Stop behavior; and
- corrupt configuration and Host Key database recovery.

OEM-specific power management and force-stop behavior must also be confirmed on
target devices.

## Review history

### v0.3.8

- Removed only `diffie-hellman-group-exchange-sha256` from each Session's current KEX list.
- Preserved all other JSch KEX algorithms and their order.
- Added a regression test for removal and ordering.

### v0.3.7

- Added Android 12+ `dataExtractionRules` alongside `allowBackup=false`.
- Explicitly excluded `files/keys/` from cloud backup and device transfer.
- Left the application ID, SSH state machines, and backup architecture unchanged.

### v0.3.6 and earlier hardening

- Added Background Restricted detection to Start and runtime power-policy checks.
- Disabled SSH `none`, password prompts, and unsigned public-key capability queries.
- Canonicalized IPv6 Host Key identities and rejected ambiguous numeric IPv4 forms.
- Expanded Robolectric service, wake-lock, Stop, restart, and boot-gate coverage.
- Rejected dynamic and changing Gradle dependency versions.

Items intentionally left for release validation include real OpenSSH end-to-end
testing and complete Gradle dependency verification metadata generated from a
trusted dependency resolution.

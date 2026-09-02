# SSHLink

SSHLink is an Android app for keeping SSH local port-forwarding tunnels
running in the background.

It is useful when an Android device needs to reach services that are only
available from an SSH server's network, such as a home-LAN web interface,
RDP server, or development service.

For example, an app on the Android device can connect to
`127.0.0.1:33293`, while SSHLink forwards that connection to
`192.168.1.50:33293` through an SSH server.

## Features

- SSH public-key authentication with Ed25519 keys
- Multiple local port forwards
- Automatic reconnection after transient network or SSH failures
- Foreground-service operation while the screen is off
- SSH host-key pinning and verification
- Configuration export and import
- Local endpoints bound to `127.0.0.1` only

SSHLink does not support password authentication or remote port forwarding.

## Install

Download an APK from the [GitHub Releases](https://github.com/ajctrl/SSHLink/releases)
page when a release is available. If no release is available, build the app
from source using the instructions in [Developer build](#developer-build).

## Quick start

### 1. Configure the SSH connection

Open **Settings** and enter:

- **SSH Host** — the SSH server hostname or address
- **SSH Port** — normally `22`
- **Username** — the SSH account to use

Under **SSH Key**, select **Generate / Regenerate**, then use **Copy public
key**.

Add the copied public key to the SSH account's `~/.ssh/authorized_keys` file.
The private key remains in SSHLink's app-private storage and is not included
in configuration exports.

### 2. Add a local forward

In **Local Forwards**, select **Add Forward** and enter a row such as:

```text
Local Port: 33293
Destination: 192.168.1.50:33293
```

The local address is fixed to `127.0.0.1`. The destination is resolved and
reached from the SSH server's network, not from the Android device directly.

Select **Save**, return to the main screen, and select **Start**.

### 3. Use the forwarded service

Configure the client app on the Android device to connect to:

```text
Host: 127.0.0.1
Port: 33293
```

To add another service, create another forward with a different local port.

## SSH server requirements

The SSH server must allow public-key authentication and local forwarding.
For example, the relevant `sshd_config` settings may include:

```text
PubkeyAuthentication yes
AllowTcpForwarding local
```

After changing the SSH server configuration, reload or restart its SSH
service according to the server's operating system. The SSH account must
also be able to reach each forward destination from the server.

## Security and limitations

- Only Ed25519 public-key authentication is supported.
- The local listener is never exposed to other network devices; it binds to
  `127.0.0.1` only.
- SSH host keys are pinned after first use. If a pinned host key changes,
  verify the change independently before forgetting the pin in Settings.
- Android battery-optimization restrictions can interrupt long-running
  tunnels. SSHLink asks for the required power-settings exemption.
- On Android 13 and later, notification permission may be requested when the
  tunnel starts because the tunnel runs as a foreground service.
- Configuration backup excludes the private key and pinned SSH host keys.

## Troubleshooting

**The tunnel will not start**

Confirm that the SSH settings are valid, an Ed25519 key exists, the public
key is installed on the server, and battery optimization is disabled for
SSHLink.

**The SSH connection works, but the forwarded service does not**

Check that the destination is reachable from the SSH server, the destination
port is open, and the local client is using `127.0.0.1` with the configured
local port.

**The host-key warning appears**

Do not clear the pinned key without checking the server's expected host-key
fingerprint. If the server was intentionally reinstalled or its host key was
rotated, use **Forget pinned SSH host key** in Settings and reconnect.

## Documentation

- [SSHLink specification](docs/ssh-tunnel-spec.md)
- [Implementation and validation notes](docs/implementation-notes.md)

## Developer build

The project requires Android SDK 36 and Java 17. Run the unit tests, lint,
and an unsigned release build with:

```bash
./gradlew --no-daemon clean testDebugUnitTest lintRelease assembleRelease
```

The unsigned APK is written to
`app/build/outputs/apk/release/app-release-unsigned.apk`.

### Local signing

Create and securely back up a release keystore. Losing it prevents future
versions from updating an installed app signed with that key.

```bash
keytool -genkeypair -v \
  -keystore sshlink-release.jks \
  -alias sshlink \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Copy `keystore.properties.example` to `keystore.properties`, then enter the
keystore path, alias, and passwords. Both files containing keys or passwords
are excluded from Git. Build and verify the signed APK with:

```bash
./gradlew assembleRelease
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose \
  app/build/outputs/apk/release/app-release.apk
```

The same four values may be supplied as environment variables:
`SIGNING_KEYSTORE_FILE`, `SIGNING_KEY_ALIAS`, `SIGNING_STORE_PASSWORD`, and
`SIGNING_KEY_PASSWORD`. Without signing values, Gradle produces an unsigned
APK; a partial configuration fails the build.

For tagged GitHub Actions builds, configure the repository secrets
`SIGNING_KEYSTORE_BASE64`, `SIGNING_KEY_ALIAS`, `SIGNING_STORE_PASSWORD`, and
`SIGNING_KEY_PASSWORD`. `SIGNING_KEYSTORE_BASE64` is the single-line output
of:

```bash
base64 -w 0 sshlink-release.jks
```
When a tag such as `v0.3.9` is pushed, GitHub Actions uses it as the APK's
`versionName`, derives its Android `versionCode`, signs the APK, and creates
a GitHub Release with the APK and its SHA-256 file. Release tags must use the
exact `vMAJOR.MINOR.PATCH` format.

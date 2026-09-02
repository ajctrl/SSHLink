# SSHLink Android App Specification

## Objectives

- Maintain a persistent SSH tunnel on an Android device.
- Allow other apps to continue using their normal destination hostnames.
- Preserve TLS certificate hostname verification.
- Provide access to services on a LAN through SSH.

## Design Principles

- Implement the app as a dedicated SSH tunneling application.
- Include no unnecessary functionality.
- No advertisements.
- No telemetry.
- The only external communication performed by the app is the SSH connection.

## SSH Features

Supported:

- SSH connections
- SSH Local Port Forwarding (`-L`)
- Public-key authentication
- Ed25519 keys
- OpenSSH-compatible key format

Not supported:

- Remote Port Forwarding
- Password authentication

## SSH Key Management

- Generate keys through an explicit user action.
- Store the private key in the app's internal storage.
- Display the public key.
- Allow the public key to be copied.

If the private key does not exist:

- A key can be generated during initial setup.
- Notify the user if an existing configuration is present.
- Allow the key to be regenerated when necessary.

## SSH Connection Settings

Persist the following values:

- SSH Host
- SSH Port
- Username
- Key to use
- KeepAlive settings

## Port Forwarding

### Multiple Forwards

The app supports registering multiple forwards.

Settings for each forward:

- Name (optional)
- Local Port
- Destination (`host:port`, for example `192.168.1.1:3389`)

Example:

```text
127.0.0.1:33293
    ↓
192.168.1.50:33293
```

Restrictions:

- The local endpoint must bind only to `127.0.0.1`.
- The local endpoint must not be exposed to external networks.

## Connection Persistence

- Run as a Foreground Service.
- Continue running while the screen is off.
- Detect SSH disconnections.
- Reconnect automatically.

After a disconnection:

```text
SSH disconnected
    ↓
Reconnect
    ↓
Recreate forwards
```

## KeepAlive

Supported settings:

- `ServerAliveInterval`
- `ServerAliveCountMax`

Objectives:

- Prevent NAT timeouts.
- Prevent disconnection during periods of inactivity.

## Network Changes

Supported behavior:

- Detect Wi-Fi network changes.
- Detect switches to or from mobile data.
- Reconnect SSH.
- Resolve DNS again.

## DNS Design

### DNS for the SSH Destination

This applies to the destination of the SSH connection.

Example:

```text
ssh.mysite.com
```

Requirements:

- Resolve DNS when connecting.
- Resolve DNS again after a network change.

### DNS for Forward Destinations

This applies to the Remote Host reached from the SSH server.

- Resolve the hostname on the SSH server.
- Do not depend on Android-side DNS resolution.

### DNS Used by Client Apps

This applies to other apps that use the SSH tunnel.

This app does not modify their DNS configuration.

Examples:

```text
mysite.com → 127.0.0.1
```

or:

```text
mysite.com → LAN IP
```

## Configuration Persistence

Persist:

- SSH settings
- The list of forwards
- App settings

Storage format:

- JSON or SQLite

## Configuration Backup

Supported operations:

- Export
- Import

Included data:

- SSH settings
- Forward settings
- App settings

Private key:

- Must not be included in standard backups.

## Logging

Display:

- Keep logs visible in the app screen.

Log contents:

- Connection status
- Connection attempts
- Disconnections
- Reconnections
- Forward status

Behavior:

- Retain logs in memory.
- Remove the oldest entries after reaching a fixed limit.

## UI

### Main Screen

Display:

- Connection status
- SSH destination
- Forward list
- Logs

Actions:

- Start
- Stop

### Settings Screen

Settings:

- SSH configuration
- Forward management
- Backup
- Restore

## Security

- No advertisements.
- No telemetry.
- Do not request unnecessary permissions.
- Restrict Local Forward bindings to localhost.

## SSH Server Requirements

The SSH server must:

- Permit public-key authentication.
- Permit TCP forwarding.

## Build

- Use GitHub Actions.
- Produce reproducible builds.
- Generate an APK.

## Development Phases

### Phase 1

- Create the project.
- Implement configuration persistence.
- Implement key generation.
- Display the public key.

### Phase 2

- Implement SSH connections.
- Implement multiple forwards.

### Phase 3

- Implement the Foreground Service.
- Implement KeepAlive.
- Implement automatic reconnection.

### Phase 4

- Handle network changes.
- Implement backup and restore.
- Improve stability.

## Android Implementation Constraints

The following constraints do not add new features. They define how the preceding
requirements must be implemented on current Android versions.

- Use TOFU pinning for SSH Host Key verification and reject Host Key changes after the first successful connection.
- Restore a user-started tunnel after a device restart or app update only when retry has not been blocked by a terminal authentication or Host Key error.
- Fail closed instead of silently degrading when Android Background Restricted, Battery Optimization, or Low Power Standby settings prevent reliable persistent operation, and guide the user to the relevant system setting.
- Restrict Local Ports to the non-privileged range available to ordinary, non-root Android apps: `1024..65535`. Continue to bind only to `127.0.0.1`.
- Store the SSH private key in the app-internal `files/keys/` directory and exclude it from app Export and Import. On Android 12 and later, also exclude it explicitly from Cloud Backup and Device-to-Device transfer through `dataExtractionRules`.
- Use the SSH library's default KEX configuration except for disabling `diffie-hellman-group-exchange-sha256`, which has known security concerns. Do not maintain a comprehensive application-specific KEX allow-list.

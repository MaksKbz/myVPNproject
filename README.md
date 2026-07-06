# myVPNproject — Android DPI Bypass VPN Client

This repository contains the architecture, configuration, and source code files for an Android VPN application inspired by the **B4 (Bye Bye Big Bro)** packet-level censorship bypass multitool and the **Netrix/Zapret** concepts.

The application captures network traffic using Android's native `VpnService` (rootless mode) and processes TCP packets (such as TLS ClientHello split/fragmentation and QUIC blocking) to circumvent DPI-based blockages.

---

## Architecture & How It Works

```
 ┌─────────────┐       ┌───────────────┐       ┌────────────────────────┐
 │ Android App │ ───>  │  VpnService   │ ───>  │     Go-based Engine    │
 │ (Kotlin UI) │       │ (TUN Device)  │       │ (TCP Split/Obfuscator) │
 └─────────────┘       └───────────────┘       └────────────────────────┘
                                                            │
                                                            ▼
 ┌─────────────┐       ┌───────────────┐       ┌────────────────────────┐
 │ Real Server │ <───  │ Provider DPI  │ <───  │      Modified TCP      │
 │ (Decrypted) │       │  (Confused)   │       │        Packets         │
 └─────────────┘       └───────────────┘       └────────────────────────┘
```

1. **Android VpnService**: Intercepts IP packets from the Android system and redirects them to a virtual TUN interface.
2. **Go/Kotlin Processor (Userspace)**: Reads raw IP packets from the TUN interface, parses them down to TCP/UDP, and applies DPI-bypass tactics.
3. **ByPass Strategies**:
   * **TCP ClientHello Segment Splitting**: Splitting the TLS ClientHello at a specific index (e.g., in the middle of the SNI domain) so that DPI cannot inspect the hostname.
   * **QUIC Blocking (UDP 443 Drop)**: Dropping QUIC packets to force modern services (like YouTube) to fall back to TCP, where fragmentation is highly effective.
   * **Faking / Decoy Packet Injection**: Generating a fake payload packet with low TTL so the provider's DPI processes the decoy while the real server receives the genuine segments.

---

## Project Structure

```
myVPNproject/
├── app/
│   ├── build.gradle.kts           # App gradle build dependencies
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml # Permissions (BIND_VPN_SERVICE)
│           ├── java/com/makskbz/myvpnproject/
│           │   ├── MainActivity.kt          # UI with Jetpack Compose
│           │   └── vpn/
│           │       ├── BypassVpnService.kt  # Rootless Android VpnService
│           │       └── PacketProcessor.kt   # Low-level TCP splitting logic
│           └── res/                         # UI layouts and resources
├── build.gradle.kts                 # Project-level build script
├── settings.gradle.kts              # Module configuration
├── .gitignore                       # Android build patterns to ignore
└── README.md                        # Documentation
```

---

## Build Requirements

To compile the APK, import this project into **Android Studio** (Koala or newer):
* **JDK**: 17+
* **Android SDK**: API 21+ (Lollipop) up to API 34+ (Android 14)
* **Build System**: Gradle Kotlin DSL (`.gradle.kts`)
* **Optional (for Go-compiled engines)**: Go 1.21+ and `gomobile` to compile native Go code into an `.aar` package.

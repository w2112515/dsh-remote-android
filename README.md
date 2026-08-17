# DSH Remote Android

**Android phone client for [DSH Remote Host](https://github.com/w2112515/dsh-remote-host).**

Install the Host plugin on a Windows PC running [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) `dsh web`, then sideload **one APK** from [Releases](https://github.com/w2112515/dsh-remote-android/releases). This repository is the Android app, not a DSH plugin. A marketplace pack cannot install it.

English | [中文](README.zh-CN.md)

<p>
  <img src="docs/phone/sessions.png" width="220" alt="DSH Remote Android session list, grouped by Host project">
  <img src="docs/phone/chat.png" width="220" alt="DSH Remote Android chat with usage, model, and agent preset">
  <img src="docs/phone/create.png" width="220" alt="Create a session on an existing Host workspace or a new folder">
</p>
<p>
  <img src="docs/phone/hosts.png" width="220" alt="Paired Host status on the Android client">
  <img src="docs/phone/artifacts.png" width="220" alt="Host artifacts list on the Android client">
</p>

<p><sub>Real vivo phone, paired over LAN. Host name and LAN address redacted.</sub></p>

## What the app does

| Screen | Role |
| --- | --- |
| Sessions | Host directory grouped by project / workspace label. A session this phone just created stays visible without reconnecting. |
| Chat | Live projection: messages, tools, usage when the Host serves it, model and agent preset. Trajectory and export sit next to chat. |
| New session | Pick an existing Host workspace, or ask the Host to create a folder under an allowed parent. Full paths never leave the PC. |
| Approvals | Pending tool approvals. |
| Artifacts | Host-projected file outputs. “Unreviewed” is a marker on this device. |
| Hosts | Pair another PC, see online / idle, unpair. |

Pairing is Noise (`XXpsk3` / `IK`): same Wi-Fi, scan the Host QR, confirm the eight-digit code on the PC.

## Install

1. On the PC:

   ```powershell
   dsh plugin --profile web add @w2112515/dsh-remote-host
   ```

   Restart `dsh web`. Open **Settings → Mobile access**, turn on nearby discovery.
2. Download the APK from [Releases](https://github.com/w2112515/dsh-remote-android/releases) and sideload it.
3. Join the same Wi-Fi, scan the QR, confirm the code on the PC.

Host docs, limits, and FAQ: [dsh-remote-host](https://github.com/w2112515/dsh-remote-host#readme). Short machine-readable summary: [llms.txt](llms.txt).

This is a **debug** APK (`dev.dshremote.gate0c`). Windows x64 is the reviewed Host platform. Same LAN only — no public relay in this release.

## Build

Android Studio, or:

```powershell
.\gradlew.bat assembleDebug
```

## Related

| Piece | Repository |
| --- | --- |
| Host plugin | https://github.com/w2112515/dsh-remote-host |
| This APK | https://github.com/w2112515/dsh-remote-android |
| Marketplace pack listing | https://github.com/w2112515/dsh-remote-pack |

## License

See the repository license file. Pairing and Host security are implemented in the Host plugin.

# sound_transmitter

Stream whatever your Windows PC is playing to your Android phone, live, from anywhere.

```
PC audio ──► capture (WASAPI loopback) ──► Opus ──► WebSocket (over Tailscale) ──► Android app ──► phone speakers
```

There are two apps:

- **`sound-transmitter.exe`** runs on the PC, in the system tray.
- **The Android APK** runs on the phone and keeps playing with the screen off.

## Quick start

You need the two files, which come from this repository's **Releases** page (or build them yourself, see [Build it](#build-it)):

- `sound-transmitter.exe`
- `soundtransmitter-release.apk`

### 1. Set up Tailscale (to use it away from home)

[Tailscale](https://tailscale.com) puts your PC and phone on one private network, so the phone can reach the PC from anywhere without opening any port on your router.

1. Install Tailscale on the **PC** and on the **phone**.
2. Sign in to **both with the same account**.
3. On the PC, find its Tailscale address. Either:
   - run `tailscale ip -4` in a terminal (it looks like `100.x.y.z`), or
   - open the Tailscale admin console and copy the machine's name or IP.
4. Keep Tailscale running on the phone while you listen.

On the same Wi-Fi you can skip Tailscale and use the PC's local IP instead.

### 2. Run the PC app

1. Double-click `sound-transmitter.exe`. No installer and no Python needed.
2. When Windows Firewall asks, allow **private networks**.
3. A blue icon appears in the system tray (behind the `^` arrow at the right of the taskbar). Use **Quit** in its menu to stop it.

### 3. Install the Android app

1. Copy `soundtransmitter-release.apk` to the phone and open it.
2. Allow "Install unknown apps" for the app you opened it from, if Android asks.

### 4. Listen

1. Play something on the PC.
2. Open **SoundTransmitter** on the phone.
3. Enter the PC's **Tailscale address** (or local IP) and the port `8765`, then press **Connect**.

The audio should start playing on the phone. You can lock the screen and keep listening.

## Build it

### PC exe

Requires [uv](https://docs.astral.sh/uv/), which fetches Python 3.14 for you.

```powershell
uv sync
uv run pyinstaller --onefile --noconsole --name sound-transmitter --collect-all pyogg scripts/run_server.py
```

The result is `dist\sound-transmitter.exe`, a single portable file. Quit any running copy first, because Windows locks the file and the build fails with `Access is denied`.

To run from source instead of building:

```powershell
uv run sound-transmitter
```

### Android APK

Requires Android Studio.

1. Open the `android/` folder and let Gradle sync.
2. **Debug build:** Build > Build Bundle(s) / APK(s) > Build APK(s). The file is `android\app\build\outputs\apk\debug\app-debug.apk`.
3. **Release build:** Build > Generate Signed App Bundle / APK > APK. Create a keystore the first time and keep it outside the repository. Back it up, because without it you can't publish updates that install over the old app.

You can also press **Run** with the phone connected over USB (USB debugging on) to install straight from Android Studio.

## Settings

Settings live in [src/sound_transmitter_pc/config.py](src/sound_transmitter_pc/config.py). Rebuild the exe after changing them.

| Setting | Default | Meaning |
|---|---|---|
| `HOST` | `0.0.0.0` | address to listen on. `0.0.0.0` means every network interface. |
| `PORT` | `8765` | port to listen on |
| `CODEC` | `opus` | `opus` (about 8 KB/s) or `pcm` (raw, about 192 KB/s) |
| `BITRATE` | `64000` | Opus bitrate in bits per second |

> There is no authentication. Only run it on a network you trust. Over Tailscale only your own devices can reach it. You can set `HOST` to the PC's Tailscale address so it doesn't listen on other interfaces at all.

## How it works

1. When a phone connects, the PC sends one **text** frame:
   `{"rate": 48000, "channels": 2, "format": "int16", "codec": "opus"}`
2. After that it sends **binary** frames continuously, each one a 20 ms chunk: an Opus packet, or raw 16-bit PCM when the codec is `pcm`.
3. A phone that connects mid-stream starts from the current audio. Reconnecting is just a new connection.

## Test the PC side without the phone

With the PC app running:

```powershell
uv run scripts/test_client.py
```

It prints the config message and then the bytes received per second (about 8,000 with Opus, about 192,000 with PCM).

## Project layout

```
src/sound_transmitter_pc/
├─ capturing.py   WASAPI loopback capture (pyaudiowpatch)
├─ audio.py       Opus encoder (libopus through PyOgg)
├─ transport.py   WebSocket server and broadcast loop
├─ tray.py        system tray icon, runs the server in a thread
└─ config.py      settings and logging
scripts/
├─ run_server.py  entry point used by PyInstaller
└─ test_client.py throwaway client that prints received bytes per second
android/          the Android app (Kotlin, OkHttp, MediaCodec, AudioTrack)
```

## Troubleshooting

- **The phone can't connect:** check the address and port, that Tailscale is running on both devices, that the PC app is running, and that Windows Firewall allows it on private networks.
- **Connected but silent:** something has to be playing on the PC. Loopback sends silence otherwise.
- **`Access is denied` from `uv`:** the app is still running and holds `sound-transmitter.exe`. Quit it from the tray first.
- **`opus_encoder_create failed`:** Opus only supports 8, 12, 16, 24 and 48 kHz. Set the Windows output device to 48000 Hz, or set `CODEC` to `pcm`.
- **Something sounds wrong:** switch `CODEC` to `pcm` to rule out the codec.
- **No log window:** the exe has no console. Read `sound-transmitter.log`, which it writes next to itself.

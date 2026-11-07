# NOVA IPTV — Samsung Smart TV (Tizen)

Tizen web app for Samsung TVs from 2020 onward (Tizen 5.5+). Same subscriptions as the Android app
(Xtream Codes, M3U URL), playback through Samsung's native AVPlay engine (HLS, MPEG-TS, MP4, MKV).

## Install on your TV (no store needed)

1. **TV — developer mode**: open *Apps*, press `1 2 3 4 5` on the remote, switch *Developer mode* **On**,
   enter your computer's IP address, then restart the TV.
2. **Computer**: install [Tizen Studio](https://developer.tizen.org/development/tizen-studio/download).
   In *Package Manager* → *Extension SDK* install **TV Extensions** and **Samsung Certificate Extension**.
3. *Tools → Device Manager → Remote Device Manager* → add the TV's IP → connect.
4. *Tools → Certificate Manager* → **+** → *Samsung* → *TV* → sign in with your Samsung account.
   (The connected TV's DUID is added automatically.)
5. *File → Import → Tizen → Tizen Project* → choose this `tizen` folder.
6. Right‑click the project → **Run As → Tizen Web Application**. NOVA IPTV appears on the TV.

### Command line alternative

```bash
tizen package -t wgt -s <your-certificate-profile> -- tizen
sdb connect <TV-IP>
tizen install -n tizen/NOVA\ IPTV.wgt -t <device-name>
```

## Remote control

| Key | Action |
|---|---|
| Arrows / OK | Navigate / select |
| BACK | Go back (exit confirmation on the home screen) |
| CH ▲ ▼ or ▲ ▼ while watching | Change channel |
| 0–9 | Jump to channel number |
| ◀ ▶ (VOD) | Seek 10 s · ⏪ ⏩ seek 30 s |
| INFO | Show / hide programme info |
| Red / Green / Yellow | Audio track / Subtitles / Aspect ratio |

## Notes

* Credentials are encrypted with a non‑extractable WebCrypto key in the app's private storage.
* The TV guide uses the provider's Xtream EPG API (now/next and a per‑channel daily schedule with catch‑up).
  XMLTV guides for M3U playlists are not loaded on the TV version.

<img src="ic_launcher_logo.png" width="150" height="150">

# PylaAndroid

PylaAndroid is an on-device PylaAI port for Android — no root and no ADB required.

- **Upstream project:** [countryside99/PylaAndroid](https://github.com/countryside99/PylaAndroid)
- This community contribution carries an **experimental** Android development snapshot for review. The original authors and attribution are preserved; test builds are hosted separately on the [contribution fork's releases page](https://github.com/Fetzi672/PylaAndroid/releases).

Every PylaAI file such as `play.py` or `trophy_observer.py` is fully rewritten in Kotlin as `Play.kt`, `TrophyObserver.kt` and the others.

# Development status ⚠️

**EARLY BETA / EXPERIMENTAL.** This is not a finished product and it is **not a guarantee of unattended gameplay** on any phone. Expect bugs, and always supervise the app while it runs. Some functions are reliable, others are only partially reliable — see [docs/FORK_STATUS.md](docs/FORK_STATUS.md) and [docs/KNOWN_ISSUES.md](docs/KNOWN_ISSUES.md) before you rely on anything.

# How to use
Follow these steps to start using **PylaAI** on your Android device.

### 1. Install the App
- Download a build from the **Releases** page when one is published, or
- Build the APK yourself from the source code.

### 2. Grant Accessibility Permission
1. Open **PylaAndroid**.
2. Tap **Enable Accessibility Service**.
3. Tap **Installed apps**
4. If the permission is blocked by Android, go to:

```
Settings → Apps → PylaAndroid → ⋮ (Top-right menu) → Allow Restricted Settings
```

4. Return to **Settings → Accessibility** and enable the **PylaAI Accessibility Service**.


**Game MUST be in English!**


### 3. Grant Screen Capture (MediaProjection) Permission
1. Press **Start Bot**. (If you like it next to the start button there is an **overlay button** where you can activate a floating overlay to control the bot)
2. Android will display a **Screen Capture** permission dialog.
3. Tap **Start Now** (or **Allow**, depending on your Android version).
4. If prompted, choose **Entire Screen**.

> **Note:** This permission is required for the bot to analyze the screen. No screenshots or recordings are stored or uploaded.

### 4. Enjoy external auto-farming
Once pressed **Start Bot**:

- **Brawl Stars** will launch automatically.
- **Enjoy!**

# Playstyle builder

The optional [playstyle builder](tools/playstyle-builder/) generates a custom `.pyla` playstyle in
your browser. Open `tools/playstyle-builder/index.html`, pick a preset, tune it and download the
file — no server, account or upload involved. Import it in PylaAndroid via **Playstyles → Import
custom .pyla**, then select the playstyle and check the logs after starting. The builder only
creates the script; detections, brawler data and native input gates stay authoritative, and import
success is not proof of gameplay behaviour. See the
[builder README](tools/playstyle-builder/README.md) for limits and the included checks.

# Can I use on Emulator?
Yes! You can use **PylaAndroid's APK** also directly on your android **emulator**!

# Diagnostics

PylaAndroid can prepare a diagnostic report to help diagnose problems. Upload is **opt-in**: you must confirm consent before anything is uploaded. Reports are redacted and are intended to exclude screenshots, raw OCR text and credentials — this is the design intent, not a promise that a report can never contain anything sensitive. Full details live in [docs/DIAGNOSTICS.md](docs/DIAGNOSTICS.md).

> Sharing a report ID publicly may allow others to read it; review before posting.

# Credits
**Developers of original project:**
- AngelFire
- iyordanov

## License

The project is distributed under the **ANCS-1.0** license — see [LICENSE](LICENSE).

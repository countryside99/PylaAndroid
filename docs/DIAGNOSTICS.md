# Optional diagnostic reports

PylaAndroid has **no network access**: the app does not request the Android `INTERNET` permission and cannot send anything by itself. A diagnostic report only leaves your device as a file that you save or share yourself. Sharing a report is not required to use the app or to report a bug.

## Exporting a report

The **Event log** card on the home screen offers two buttons:

- **Save log** opens the system file picker and writes the report to the location you choose, as `pyla-diagnostics-<date>-<time>.txt`.
- **Share log** opens the system share sheet with the same file, for example to send it through a messenger. The file is kept in the app cache; each new share replaces the previous one.

The report is the event log at the moment you press the button. Cancelling the file picker or the share sheet sends and writes nothing outside the app.

Attach the file to a GitHub bug report or send it to whoever is helping you. **GitHub issues are public**: read the file before you post it.

## Contents and privacy limits

Reports contain bounded PylaAndroid process-session diagnostic events, the app version, Android API level and relevant technical metadata such as action outcomes and timing. They also contain a compatibility line with the device manufacturer and model, CPU ABI, screen and capture resolution, CPU core count and RAM size; it is needed to reproduce device-specific failures and contains no serial number, Android ID, IMEI, account or network identifier. Credential-like values are sanitized before log retention. The logger is designed not to collect screenshots, raw OCR output, unrelated device Logcat or stable device identifiers.

This is not a guarantee that arbitrary diagnostic text can never contain sensitive information. The file is plain text: review it before sharing it publicly. Diagnostics do not grant game-control authority or silently restart gameplay.

## Retention

The event log is kept in memory only and is cleared when the app process restarts. An exported file stays where you saved it until you delete it. The copy made for **Share log** lives in the app cache and is removed with the next share, when Android clears the cache, or when the app is uninstalled.

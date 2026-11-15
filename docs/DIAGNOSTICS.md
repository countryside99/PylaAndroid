# Optional diagnostic reports

Diagnostics are **opt-in**. In the development build, choose **Sent Log**, inspect the confirmation information, and select **Confirm** to submit the frozen report. **Cancel**, Back or dismissing the dialog does not submit it. Sharing a report is not required to use the app or report a bug.

## Service and access

The current viewer is **https://pyla-logs.fetzi-ai.de/**. This build uploads to `https://pyla-logs.fetzi-ai.de/v1/reports`.

After a successful submission, the app exposes a `pyla-android-…` report reference. Enter the complete reference in the viewer and complete Cloudflare Turnstile human verification. The viewer checks the challenge server-side before reading a report. This is a report-ID capability plus an anti-bot challenge, **not an account login or proof that the reader is the uploader**.

**Anyone who obtains your report ID may read the report after completing human verification.** Review the report before sharing its ID publicly. GitHub issues are public: the bug-report form deliberately leaves the ID field optional. Do not post credentials or private log contents. The separate owner/admin API requires a server-only credential and is not available through the upload capability embedded in this build.

## Contents and privacy limits

Reports contain bounded PylaAndroid process-session diagnostic events, the app version, Android API level and relevant technical metadata such as action outcomes and timing. They also contain a compatibility line with the device manufacturer and model, CPU ABI, screen and capture resolution, CPU core count and RAM size; it is needed to reproduce device-specific failures and contains no serial number, Android ID, IMEI, account or network identifier. Credential-like values are sanitized before log retention. The logger is designed not to collect screenshots, raw OCR output, unrelated device Logcat or stable device identifiers.

This is not a guarantee that arbitrary diagnostic text can never contain sensitive information. Review before submission or public sharing. Diagnostics do not grant game-control authority or silently restart gameplay.

## Retention

Reports are stored on Cloudflare R2, with bounded report metadata in a Durable Object. **Report access expires after seven days**, and the application rejects expired reads. The R2 bucket has an enabled seven-day object-age deletion rule for `reports/`.

Physical deletion is performed asynchronously by Cloudflare's lifecycle processing; it need not occur at the exact expiry instant. Access expiry and physical deletion are different guarantees. Do not interpret this documentation as a promise that every stored byte disappears at precisely seven days.

## Capacity and build configuration

The service currently allows **20 new reports per UTC day across all users combined**. Logs are limited to 256 KiB and requests to 300 KiB. An exact duplicate ID/payload is idempotent; reusing an ID with different content is rejected. No dedicated per-IP/burst limiter was confirmed. Capacity can be exhausted, so submission is best-effort; file a bug without an ID when necessary.

The pre-release uses an externally supplied **upload-only capability**. As with any client-embedded capability, it is extractable and does not authenticate an individual uploader. It does not grant read/list/delete or Cloudflare administration access. Owner/private-reader credentials and Turnstile secrets are never part of the APK or source distribution. An ordinary CI build without `PYLA_DIAGNOSTIC_UPLOAD_TOKEN` cannot submit to the owner's service.

The public service is optional and maintained separately from the original upstream project; this contribution does not make the original maintainer responsible for operating it. Backend authentication, quotas or availability may change. Operators must keep app consent text and these instructions in sync with the actual service contract.

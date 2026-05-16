# BCR Headless Test

<img src="app/images/icon.svg" alt="app icon" width="72" />

> Test build: this branch/package is temporarily labeled as
> `BCR Headless Test` with module id `bcr.headless.test` and version
> `1.1.0-test.3` so it can be installed beside the original `1.0.0`
> `bcr.headless` release.

[![latest release badge](https://img.shields.io/github/v/release/wjdob/BCR-Headless-Test?sort=semver)](https://github.com/wjdob/BCR-Headless-Test/releases/latest)
[![license badge](https://img.shields.io/github/license/wjdob/BCR-Headless-Test)](./LICENSE)

BCR Headless is a headless call recorder module for rooted Android devices. It is an architectural rebuild of the original BCR project that keeps the recorder inside the module directory, exposes configuration through a module WebUI, and avoids installing a visible companion app.

<img src="app/images/UI1.jpg" alt="UI top part screenshot" width="200" /> <img src="app/images/UI2.jpg" alt="UI bottom part screenshot" width="200" />

## Credits

This project is based on the original BCR project by Andrew Gunnerson (`chenxiaolong`) and its contributors:

* Original project: https://github.com/chenxiaolong/BCR
* Original author: Andrew Gunnerson
* Original contributors: see the upstream repository history and contributors list

## What Changed

The original BCR relied on a system app and privileged Android components. This rebuild pivots to a different architecture:

* No visible Android settings app is installed
* The helper APK is stored inside the module under `tools/bcr-headless.apk`
* `skip_mount` avoids creating a `/system` overlay footprint
* Configuration lives in module-local files and mirrors into KernelSU module config when available
* A module WebUI is the primary configuration surface for KernelSU and standalone KSUWebUI-compatible apps

This design exists to reduce the user-space surface that security-sensitive apps can inspect easily while still keeping call recording functional.

## Current Features

* Headless boot-time recorder daemon
* Works as a Magisk or KernelSU module
* WebUI implementation
* Enable or disable recording from the WebUI
* Configure output directory
* Configure minimum recording duration
* Optional in-module recording log with a dedicated WebUI view
* Best-effort "Open output folder" action from the WebUI
* Open individual recordings from the recorded-calls view
* Experimental offline transcription queue with speaker-labeled transcripts
* Optional experimental stereo uplink/downlink WAV capture for future diarization
* ABI-aware transcriber component preparation for Android `whisper-cli`
* Separate debug view for runtime status, probe output, and logs
* Recording files saved directly to a plain filesystem path

## Current Limitations

This rebuild is intentionally narrower than the original BCR app:

* Output is currently WAV/PCM only
* Transcription is experimental and depends on published transcriber tool
  release assets plus local models
* No cloud transcription backend is included
* Speaker labels are best-effort diarization labels such as `Speaker A` and
  `Speaker B`; mono recordings require a TinyDiarize-capable model, while
  stereo recordings use whisper.cpp stereo diarization
* The output path is a plain filesystem path, not a SAF tree. Don't ask for enhancement.
* Auto-record rules are not ported
* Contacts integration is not ported
* Best-effort phone-number resolution for filenames and recording-log entries. Tested OK but YMMV.
* Filename templates are not ported
* The current working monitor prefers polling on ROMs where framework callbacks are unavailable
* Debug/runtime details are module-local and do not try to recreate every original app workflow

## Usage

1. Build or download the release zip.
2. Flash it as a Magisk or KernelSU module.
3. Reboot.
4. Open the module WebUI.
5. Configure:
   * `Recording enabled`
   * `Output directory`
   * `Minimum duration`
   * `Recording log` if desired
6. Save changes.

For Magisk installs, the same module can be opened from a standalone KSUWebUI-compatible app.

## WebUI Notes

The main recorder screen is intended for normal use:

* Save changes
* Reset defaults
* Toggle recording
* Open the output folder

The recordings screen is intended for review of captured recordings:

* View captured call recordings
* View call recording capture status
* Open the recordings
* Clear recording log

The transcriber screen is intended for offline post-processing:

* Enable or disable the experimental transcriber
* Configure transcript directory, source language, and `.txt`/`.docx` output
* Prepare module-local whisper.cpp/model component directories
* Auto-select an Android `whisper-cli` package from the transcriber tools
  manifest, or provide a direct URL override for testing
* Select one, multiple, or all recordings for the queue
* Skip, overwrite, or cancel when matching transcripts already exist
* Pause, resume, stop, remove, and clear queued transcription jobs

The debug screen is intended for troubleshooting:

* Enable or disable global debug tracking
* Refresh runtime state
* Restart the daemon
* Run a probe
* Show daemon logs
* Inspect transcriber status, component downloads, queued jobs, and transcriber logs

## Shell Control

The module can also be controlled directly:

```bash
su -c sh /data/adb/modules/bcr.headless.test/action.sh status
su -c sh /data/adb/modules/bcr.headless.test/action.sh config list
su -c sh /data/adb/modules/bcr.headless.test/action.sh reset-config
su -c sh /data/adb/modules/bcr.headless.test/action.sh restart
su -c sh /data/adb/modules/bcr.headless.test/action.sh probe
su -c sh /data/adb/modules/bcr.headless.test/action.sh logs
su -c sh /data/adb/modules/bcr.headless.test/action.sh transcriber status
su -c sh /data/adb/modules/bcr.headless.test/action.sh transcriber list
su -c sh /data/adb/modules/bcr.headless.test/action.sh transcriber enqueue skip /sdcard/Recordings/BCR/example.wav
```

## Configuration Keys

The main module config keys are:

* `recording.enabled`
* `output.dir`
* `recording.min_duration`
* `recording.log_enabled`
* `recording.stereo`
* `debug.enabled`
* `transcriber.enabled`
* `transcriber.output_dir`
* `transcriber.language`
* `transcriber.output_format`
* `transcriber.whisper_path`
* `transcriber.model_path`
* `transcriber.tinydiarize_model_path`
* `transcriber.whisper_manifest_url`
* `transcriber.whisper_url`
* `transcriber.model_url`
* `transcriber.tinydiarize_model_url`

## Transcriber Native Tools

The transcriber uses a repo-owned Android `whisper-cli` build instead of
depending on upstream desktop release assets. The pinned whisper.cpp source and
Android build matrix live in:

```text
scripts/transcriber-tools.env
```

To update whisper.cpp later, change `WHISPER_CPP_REF`, run the
`Transcriber tools` workflow, and publish the generated assets. The workflow
builds CPU-only generic packages for `arm64-v8a`, `armeabi-v7a`, and `x86_64`,
then publishes:

* `transcriber-tools.env`
* `whisper-cli-android-<abi>.zip`
* `SHA256SUMS`

`Prepare Components` downloads the manifest, selects the best matching ABI from
`ro.product.cpu.abilist`, verifies the package SHA-256, extracts `whisper-cli`,
and runs a lightweight executable check before marking the component ready.

## Versioning

This rebuild uses its own version line and does not inherit the original BCR release numbering. The current build metadata uses:

* `1.x` for the standalone headless rebuild line
* `1.1.0-test.3` for this temporary parallel-install test build
* plain semantic version names such as `1.0.0` for stable releases

## Building

Build the release module zip with:

```bash
./gradlew zipRelease
```

Optional Gradle properties:

* `-PprojectUrl=https://github.com/<you>/<repo>`
* `-PreleaseMetadataBranch=main`

The output zip is written to `app/build/distributions/release/`.

## Publishing Notes

If you publish this project, keep explicit upstream credit to the original BCR project and preserve the GPL license and copyright notices.

Because this repository now differs substantially from the original BCR app architecture due to running in a headless mode without a helper app, this is considered a standalone project.

## License

This project remains GPLv3. See [`LICENSE`](./LICENSE).

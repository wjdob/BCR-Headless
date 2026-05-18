### Unreleased

### Version 1.1.0-test.15

- Added recorder output format selection for `WAV/PCM`, `OGG/Opus`, and
  `M4A/AAC`
- Routed the selected recording format through the headless daemon and recorder
  session instead of hard-coding WAV output
- Added recorder capability reporting for available output formats so the WebUI
  can reflect device support
- Normalized compressed recordings to temporary PCM WAV before whisper
  transcription so offline transcription and diarization work consistently for
  `OGG/Opus` and `M4A/AAC`
- Updated recording open intents to use the correct audio MIME type for the new
  formats
- Tightened WebUI copy to be shorter, clearer, and more production-oriented
- Added a recorder format selector to the WebUI
- Reduced stale installed-app surface from the original BCR app by removing the
  unused legacy app component declarations from the manifest
- Rewrote the README and changelog around the current headless module behavior
  instead of the original app-era workflows

### Version 1.1.0-test.14

- Added a recorder mode selector so users can explicitly choose `Stereo` or
  `Mono fallback` while still seeing the VOICE_CALL probe result
- Updated `Prepare Components` to offer stereo or mono-fallback preparation and
  download only the component set needed for that path
- Made transcriber readiness and component estimates follow the active recorder
  mode
- Marked non-selected transcriber models as optional instead of failed during
  the opposite preparation path

### Version 1.1.0-test.13

- Made stereo the default recorder mode when supported, with manual override in
  the WebUI
- Added live transcriber queue/component polling in the active Transcriber tab
- Added auto-queue and charging-aware auto-start controls for transcription jobs
- Preserved config, logs, queue state, and downloaded components across update
  installs
- Added local whisper package overrides plus selectable model/language settings

### Version 1.1.0-test.12

- Stabilized stereo diarization around a full-call whisper timeline plus
  left/right reference decodes for sentence-level speaker assignment
- Improved transcript quality for the default two-side stereo call case

### Version 1.1.0-test.11

- Reworked split-stereo transcription to preserve cleaner per-channel sentence
  text before speaker assignment

### Version 1.1.0-test.10

- Corrected whisper.cpp timestamp parameter usage for the stereo transcription
  path
- Filtered whisper control tokens from transcript output
- Improved stereo turn grouping with speech-region assignment

### Version 1.1.0-test.9

- Preserved module config and transcriber state across update installs
- Added a custom self-speaker label
- Added live transcriber-page polling and refreshed component size metadata

### Version 1.1.0-test.8

- Rebuilt stereo transcript turns from shorter whisper segments and token timing
  so each line carries timestamps

### Version 1.1.0-test.7

- Split true stereo WAV recordings into left/right mono passes and merged them
  back into a speaker-labeled conversation timeline

### Version 1.1.0-test.6

- Replaced shell-side component downloads with an Android-helper download path
  for better reliability on-device

### Version 1.1.0-test.5

- Replaced the whisper CLI URL override with a local-package override flow
- Reduced WebUI churn during transcriber debug refresh and scrolling

### Version 1.1.0-test.4

- Added global debug enable/disable
- Added transcriber-specific diagnostics for status, components, jobs, and logs
- Improved component-preparation error capture

### Version 1.1.0-test.3

- Added the experimental offline transcriber queue, component preparation
  workflow, and transcript generation controls
- Added stereo/mono-aware transcription preparation and offline model handling

### Version 1.0.0

- First standalone BCR Headless release
- Rebuilt the project as a headless Magisk/KernelSU module with a module WebUI
- Moved recorder control, runtime status, and recording history into the module
  directory and WebUI

### Upstream History

Versions before `1.0.0` belong to the original BCR project rather than this
headless rebuild. Upstream release history remains available at:

https://github.com/chenxiaolong/BCR/blob/master/CHANGELOG.md

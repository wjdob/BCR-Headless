### Unreleased

### Version 1.1.1

- Changed the default recording output directory to `/sdcard/Recordings/BCRHeadless`
  so fresh installs no longer reuse the original BCR default path
- Preserved existing recording and transcript locations on update while keeping
  reset/default flows aligned with the new BCR Headless directory
- Hardened recording file creation so same-second filename collisions no longer
  overwrite an existing call recording
- Kept the transcriber tools release out of the repository's "Latest" release
  slot during future helper-asset publishing

### Version 1.1.0

- Added offline transcription with queued processing, speaker labeling,
  component preparation, and transcript output in TXT or DOCX
- Added stereo-by-default recording with manual mono fallback override and
  matching stereo or mono component preparation
- Stabilized stereo diarization for the common two-side call case with
  timestamped speaker turns
- Added recorder output format selection for `WAV/PCM`, `OGG/Opus`, and
  `M4A/AAC`
- Added automatic normalization before transcription so compressed recordings
  follow the same whisper pipeline as WAV recordings
- Added auto-queue support for new recordings, optional charging-only start,
  and clean restart behavior when charging stops mid-job
- Improved transcriber progress, diagnostics, download/error reporting, and
  component metadata handling
- Preserved configuration, recording history, transcriber queue state, and
  downloaded transcriber components across in-place updates
- Simplified WebUI copy and reduced stale app-era manifest surface that no
  longer participates in the headless module path
- Updated project documentation to reflect the current headless architecture and
  feature set

### Version 1.0.0

- First standalone BCR Headless release
- Rebuilt the project as a headless Magisk/KernelSU module with a module WebUI
- Moved recorder control, runtime status, and recording history into the module
  directory and WebUI

### Upstream History

Versions before `1.0.0` belong to the original BCR project rather than this
headless rebuild. Upstream release history remains available at:

https://github.com/chenxiaolong/BCR/blob/master/CHANGELOG.md

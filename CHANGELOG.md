### Version 1.3.0

- Removed eager audio-channel inspection from normal recording listings, making
  Library and Transcribe navigation scale with larger recording sets
- Made the module's local configuration mirror the fast path for WebUI
  snapshots, with a one-time KernelSU fallback for legacy-only values
- Added a compact inline transcription-queue action to every recording row
- Removed channel filtering and unsupported custom component-source controls
- Collapsed Recorder settings by default and simplified Diagnostics to keep
  troubleshooting output on demand
- Refined search alignment, mobile bulk-selection layout, list density, and the
  light/dark visual system with a clearer operational accent

- Rebuilt the WebUI around compact Recorder, Library, Transcribe, and
  Diagnostics views with desktop side navigation and mobile bottom navigation
- Added responsive light and dark themes, keyboard-accessible dialogs and tabs,
  durable inline errors, reduced-motion support, and mobile-sized controls
- Replaced broad UI locking with per-action pending states and consolidated each
  view refresh into one framed privileged snapshot request
- Added request deduplication, stale-response protection, activity-only polling,
  server-side recording paging and filtering, and persistent multi-selection
- Added recorder output health, free-space reporting, detected capture status,
  and latest-recording context
- Added searchable Library rows with transcript filters, sort
  controls, recording actions, queue actions, and transcript preview
- Added queue retry and reordering plus processing stage, progress, elapsed time,
  ETA, and creation/start/completion timestamps
- Kept queue creation and execution manual; boot and enqueue operations do not
  start the transcription worker
- Made pause terminate the active temporary process and safely requeue the job
  from the beginning
- Added atomic transcript publication and stale-running-job recovery so an
  interruption cannot replace a valid transcript with partial output
- Added remote speaker naming and timestamped `TXT`, `DOCX`, `SRT`, `VTT`, and
  `JSON` transcript output with source and diarization metadata
- Added per-component removal, compact component readiness, live preparation
  details, output-directory health, and on-demand diagnostics
- Split the WebUI into dependency-free command, state, rendering, dialog, mock,
  and controller modules, with dynamic content rendered as text nodes
- Added Kotlin transcript/queue/library tests, WebUI contract tests, shell and
  JavaScript syntax checks, and release ZIP plus checksum handling
- Preserved module configuration, recorder history, logs, queue state,
  downloaded components, recordings, and transcripts across in-place updates

### Version 1.1.2

- Removed experimental auto-queue, charging-gated start, and charge-delay
  automation for transcription jobs; queueing and job starts are manual again
- Added an `Open Transcript Folder` action in Transcriber settings
- Made Recordings and Transcriber lists denser and more scalable for large
  histories and queues
- Reduced WebUI refresh overhead to improve responsiveness by avoiding
  unnecessary root-status polling on inactive tabs

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

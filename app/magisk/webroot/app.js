import { exec, moduleInfo, toast } from "./kernelsu.js";

const DEFAULT_MODULE_ID = "bcr.headless.test";
const DEFAULT_WHISPER_MANIFEST_URL = "https://github.com/wjdob/BCR-Headless-Test/releases/download/transcriber-tools/transcriber-tools.env";

function resolveModuleContext() {
    try {
        const info = moduleInfo() || {};
        const moduleId = info.moduleId || info.id || DEFAULT_MODULE_ID;
        const moduleDir = info.moduleDir || info.path || info.dir || `/data/adb/modules/${moduleId}`;

        return { moduleId, moduleDir };
    } catch (error) {
        return {
            moduleId: DEFAULT_MODULE_ID,
            moduleDir: `/data/adb/modules/${DEFAULT_MODULE_ID}`,
        };
    }
}

const MODULE = resolveModuleContext();

const statusOutput = document.querySelector("#status-output");
const diagnosticOutput = document.querySelector("#diagnostic-output");
const debugEnabled = document.querySelector("#debug-enabled");
const debugStatusBadge = document.querySelector("#debug-status-badge");
const debugTranscriberStatusOutput = document.querySelector("#debug-transcriber-status-output");
const debugComponentsOutput = document.querySelector("#debug-components-output");
const debugJobsOutput = document.querySelector("#debug-jobs-output");
const debugTranscriberLogsOutput = document.querySelector("#debug-transcriber-logs-output");
const runtimeBadge = document.querySelector("#runtime-badge");
const lastResult = document.querySelector("#last-result");
const lastOutput = document.querySelector("#last-output");
const lastOutputTile = document.querySelector("#last-output-tile");
const recordingEnabled = document.querySelector("#recording-enabled");
const recordingLogEnabled = document.querySelector("#recording-log-enabled");
const recordingMode = document.querySelector("#recording-mode");
const recordingModeNote = document.querySelector("#recording-mode-note");
const outputDir = document.querySelector("#output-dir");
const minDuration = document.querySelector("#min-duration");

const recorderView = document.querySelector("#recorder-view");
const recordingsView = document.querySelector("#recordings-view");
const transcriberView = document.querySelector("#transcriber-view");
const debugView = document.querySelector("#debug-view");
const recorderTab = document.querySelector("#recorder-tab");
const recordingsTab = document.querySelector("#recordings-tab");
const transcriberTab = document.querySelector("#transcriber-tab");
const debugTab = document.querySelector("#debug-tab");
const recordingLogList = document.querySelector("#recording-log-list");
const confirmOverlay = document.querySelector("#confirm-overlay");
const confirmClearButton = document.querySelector("#confirm-clear-button");
const cancelClearButton = document.querySelector("#cancel-clear-button");
const choiceOverlay = document.querySelector("#choice-overlay");
const choiceTitle = document.querySelector("#choice-title");
const choiceMessage = document.querySelector("#choice-message");
const choiceActions = document.querySelector("#choice-actions");
const choiceCheckboxRow = document.querySelector("#choice-checkbox-row");
const choiceCheckbox = document.querySelector("#choice-checkbox");
const choiceCheckboxLabel = document.querySelector("#choice-checkbox-label");

const transcriberEnabled = document.querySelector("#transcriber-enabled");
const transcriberOutputDir = document.querySelector("#transcriber-output-dir");
const transcriberLanguage = document.querySelector("#transcriber-language");
const transcriberSpeakerSelfName = document.querySelector("#transcriber-speaker-self-name");
const transcriberOutputFormat = document.querySelector("#transcriber-output-format");
const transcriberWhisperLocalEnabled = document.querySelector("#transcriber-whisper-local-enabled");
const transcriberWhisperLocalField = document.querySelector("#transcriber-whisper-local-field");
const transcriberWhisperLocalPath = document.querySelector("#transcriber-whisper-local-path");
const transcriberWhisperLocalStatus = document.querySelector("#transcriber-whisper-local-status");
const transcriberModelPreset = document.querySelector("#transcriber-model-preset");
const transcriberModelUrlEnabled = document.querySelector("#transcriber-model-url-enabled");
const transcriberModelUrlField = document.querySelector("#transcriber-model-url-field");
const transcriberModelUrl = document.querySelector("#transcriber-model-url");
const transcriberTdrzPreset = document.querySelector("#transcriber-tdrz-preset");
const transcriberTdrzUrlEnabled = document.querySelector("#transcriber-tdrz-url-enabled");
const transcriberTdrzUrlField = document.querySelector("#transcriber-tdrz-url-field");
const transcriberTdrzUrl = document.querySelector("#transcriber-tdrz-url");
const transcriberAutoQueueEnabled = document.querySelector("#transcriber-auto-queue-enabled");
const transcriberAutoQueueChargingRow = document.querySelector("#transcriber-auto-queue-charging-row");
const transcriberAutoQueueChargingHelp = document.querySelector("#transcriber-auto-queue-charging-help");
const transcriberAutoQueueChargingOnly = document.querySelector("#transcriber-auto-queue-charging-only");
const transcriberAutoQueueDelayField = document.querySelector("#transcriber-auto-queue-delay-field");
const transcriberAutoQueueDelaySeconds = document.querySelector("#transcriber-auto-queue-delay-seconds");
const transcriberEngineState = document.querySelector("#transcriber-engine-state");
const transcriberQueueState = document.querySelector("#transcriber-queue-state");
const transcriberWhisperPath = document.querySelector("#transcriber-whisper-path");
const transcriberModelPath = document.querySelector("#transcriber-model-path");
const transcriberTdrzPath = document.querySelector("#transcriber-tdrz-path");
const componentWhisperProgress = document.querySelector("#component-whisper-progress");
const componentModelProgress = document.querySelector("#component-model-progress");
const componentTdrzProgress = document.querySelector("#component-tdrz-progress");
const componentWhisperStatus = document.querySelector("#component-whisper-status");
const componentModelStatus = document.querySelector("#component-model-status");
const componentTdrzStatus = document.querySelector("#component-tdrz-status");
const transcriberRecordingList = document.querySelector("#transcriber-recording-list");
const transcriberQueueList = document.querySelector("#transcriber-queue-list");
const transcriberCurrentProgress = document.querySelector("#transcriber-current-progress");
const transcriberAllProgress = document.querySelector("#transcriber-all-progress");
const transcriberCurrentProgressLabel = document.querySelector("#transcriber-current-progress-label");
const transcriberAllProgressLabel = document.querySelector("#transcriber-all-progress-label");

const buttons = Array.from(document.querySelectorAll("button"));
const timestampFormatter = new Intl.DateTimeFormat(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    timeZoneName: "short",
});

let confirmResolver = null;
let choiceResolver = null;
let latestStatus = {};
let latestTranscriberStatus = null;
let latestComponentsStatus = {};
let latestRecordingCandidates = [];
let componentPollTimer = null;
let componentPollInFlight = false;
let activeTabName = "recorder";
const PREPARE_PROFILE_STEREO = "stereo";
const PREPARE_PROFILE_MONO = "mono";

function shellQuote(value) {
    return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

async function run(command) {
    const result = await execCommand(command);

    if (result.errno !== 0) {
        throw new Error(formatExecError(result));
    }

    return (result.stdout || "").trim();
}

async function execCommand(command) {
    return exec(command, {
        cwd: MODULE.moduleDir,
        env: {
            KSU_MODULE: MODULE.moduleId,
        },
    });
}

async function runCapture(command) {
    try {
        const result = await execCommand(command);
        return {
            command,
            ok: result.errno === 0,
            errno: result.errno,
            stdout: (result.stdout || "").trim(),
            stderr: (result.stderr || "").trim(),
        };
    } catch (error) {
        return {
            command,
            ok: false,
            errno: -1,
            stdout: "",
            stderr: String(error.message || error),
        };
    }
}

function formatExecError(result) {
    return [
        `Command failed with errno ${result.errno}`,
        (result.stderr || "").trim(),
        (result.stdout || "").trim(),
    ].filter(Boolean).join("\n");
}

function parseStatus(text) {
    const values = {};

    for (const line of text.split(/\r?\n/)) {
        if (!line || !line.includes("=")) {
            continue;
        }

        const idx = line.indexOf("=");
        values[line.slice(0, idx)] = line.slice(idx + 1);
    }

    return values;
}

function setBusy(isBusy) {
    for (const button of buttons) {
        button.disabled = isBusy;
    }

    if (!isBusy) {
        updateDebugControls();
    }
}

function setActiveTab(name) {
    activeTabName = name;
    const isRecorder = name === "recorder";
    const isRecordings = name === "recordings";
    const isTranscriber = name === "transcriber";
    const isDebug = name === "debug";

    recorderView.hidden = !isRecorder;
    recordingsView.hidden = !isRecordings;
    transcriberView.hidden = !isTranscriber;
    debugView.hidden = !isDebug;

    recorderTab.classList.toggle("active", isRecorder);
    recorderTab.classList.toggle("ghost", !isRecorder);
    recordingsTab.classList.toggle("active", isRecordings);
    recordingsTab.classList.toggle("ghost", !isRecordings);
    transcriberTab.classList.toggle("active", isTranscriber);
    transcriberTab.classList.toggle("ghost", !isTranscriber);
    debugTab.classList.toggle("active", isDebug);
    debugTab.classList.toggle("ghost", !isDebug);

    if (!isTranscriber) {
        stopComponentPolling();
    } else {
        updateTranscriberPolling();
    }

    updateDebugControls();
}

function selectHasValue(select, value) {
    return Array.from(select?.options || []).some((option) => option.value === value);
}

function ensureSelectValue(select, value, fallbackLabelPrefix = "Saved") {
    if (!select) {
        return;
    }

    if (selectHasValue(select, value)) {
        select.value = value;
        return;
    }

    const option = document.createElement("option");
    option.value = value;
    option.textContent = `${fallbackLabelPrefix}: ${value}`;
    select.appendChild(option);
    select.value = value;
}

function syncTranscriberAdvancedFields() {
    if (transcriberWhisperLocalField) {
        transcriberWhisperLocalField.hidden = !transcriberWhisperLocalEnabled?.checked;
    }
    if (transcriberModelUrlField) {
        transcriberModelUrlField.hidden = !transcriberModelUrlEnabled?.checked;
    }
    if (transcriberTdrzUrlField) {
        transcriberTdrzUrlField.hidden = !transcriberTdrzUrlEnabled?.checked;
    }

    const autoQueueEnabled = !!transcriberAutoQueueEnabled?.checked;
    if (transcriberAutoQueueChargingRow) {
        transcriberAutoQueueChargingRow.hidden = !autoQueueEnabled;
    }
    if (transcriberAutoQueueChargingHelp) {
        transcriberAutoQueueChargingHelp.hidden = !autoQueueEnabled;
    }
    if (transcriberAutoQueueDelayField) {
        transcriberAutoQueueDelayField.hidden = !autoQueueEnabled || !transcriberAutoQueueChargingOnly?.checked;
    }
}

function getSavedRecorderMode(values = latestStatus) {
    return values["recording.stereo"] === "0" ? PREPARE_PROFILE_MONO : PREPARE_PROFILE_STEREO;
}

function getRecorderModeLabel(mode) {
    return mode === PREPARE_PROFILE_MONO ? "Mono fallback" : "Stereo";
}

function getProbeStatusSummary(values = latestStatus) {
    const detectedMode = values["recording.detected_mode"] === PREPARE_PROFILE_MONO
        ? PREPARE_PROFILE_MONO
        : PREPARE_PROFILE_STEREO;
    const supportFlag = values["recording.voice_call_stereo_supported"] === "1";
    const probeNote = values["recording.voice_call_probe_note"] || "";
    const summary = supportFlag
        ? `VOICE_CALL check: stereo available (${getRecorderModeLabel(detectedMode)} detected)`
        : "VOICE_CALL check: mono fallback only";
    return probeNote ? `${summary}. ${probeNote}` : summary;
}

function updateRecordingModeNote(values = latestStatus) {
    if (!recordingModeNote || !recordingMode) {
        return;
    }

    const savedMode = getSavedRecorderMode(values);
    const pendingOverride = recordingMode.value && recordingMode.value !== savedMode;
    recordingModeNote.textContent = pendingOverride
        ? `${getProbeStatusSummary(values)} Save Changes to apply this override.`
        : getProbeStatusSummary(values);
}

function getPrepareEstimateBytes(profile, values = latestComponentsStatus) {
    const whisperBytes = Number(values["component.whisper_cli.bytes_total"] || 0);
    const modelBytes = Number(values["component.base_model.bytes_total"] || 0);
    const tdrzBytes = Number(values["component.tinydiarize_model.bytes_total"] || 0);
    return whisperBytes + (profile === PREPARE_PROFILE_MONO ? tdrzBytes : modelBytes);
}

function isTranscriberReadyForRecorderMode(status = latestTranscriberStatus, values = latestStatus) {
    const deps = status?.dependencies || {};
    return getSavedRecorderMode(values) === PREPARE_PROFILE_MONO
        ? !!deps.readyForMonoDiarization
        : !!deps.readyForStereo;
}

function updateUiFromStatus(values) {
    latestStatus = values;
    const enabled = values["recording.enabled"] === "1";

    recordingEnabled.checked = enabled;
    recordingLogEnabled.checked = values["recording.log_enabled"] !== "0";
    if (recordingMode) {
        recordingMode.value = getSavedRecorderMode(values);
    }
    debugEnabled.checked = values["debug.enabled"] === "1";
    outputDir.value = values["output.dir"] || "/sdcard/Recordings/BCR";
    minDuration.value = values["recording.min_duration"] || "0";
    transcriberEnabled.checked = values["transcriber.enabled"] === "1";
    transcriberOutputDir.value = values["transcriber.output_dir"] || `${outputDir.value || "/sdcard/Recordings/BCR"}/transcripts`;
    ensureSelectValue(transcriberLanguage, values["transcriber.language"] || "en", "Saved language");
    transcriberSpeakerSelfName.value = values["transcriber.speaker_self_name"] || "Speaker A";
    transcriberOutputFormat.value = values["transcriber.output_format"] || "txt";
    const whisperLocalPath = values["transcriber.whisper_local_path"] || "";
    transcriberWhisperLocalEnabled.checked = whisperLocalPath !== "";
    transcriberWhisperLocalPath.value = whisperLocalPath;

    const modelUrl = values["transcriber.model_url"] || "";
    if (selectHasValue(transcriberModelPreset, modelUrl)) {
        transcriberModelPreset.value = modelUrl;
        transcriberModelUrlEnabled.checked = false;
        transcriberModelUrl.value = "";
    } else {
        transcriberModelUrlEnabled.checked = true;
        transcriberModelUrl.value = modelUrl;
    }

    const tdrzUrl = values["transcriber.tinydiarize_model_url"] || "";
    if (selectHasValue(transcriberTdrzPreset, tdrzUrl)) {
        transcriberTdrzPreset.value = tdrzUrl;
        transcriberTdrzUrlEnabled.checked = false;
        transcriberTdrzUrl.value = "";
    } else {
        transcriberTdrzUrlEnabled.checked = true;
        transcriberTdrzUrl.value = tdrzUrl;
    }

    transcriberAutoQueueEnabled.checked = values["transcriber.auto_queue"] === "1";
    transcriberAutoQueueChargingOnly.checked = values["transcriber.auto_queue_require_charging"] === "1";
    transcriberAutoQueueDelaySeconds.value = values["transcriber.auto_queue_charge_delay_seconds"] || "30";
    syncTranscriberAdvancedFields();
    updateRecordingModeNote(values);
    updateTranscriberWhisperLocalStatus(values, latestComponentsStatus);

    const running = values["daemon.running"] === "1";
    const recorderState = enabled
        ? (values["recorder.state"] || (running ? "running" : "stopped"))
        : "disabled";
    runtimeBadge.textContent = recorderState;
    runtimeBadge.classList.toggle("recording", recorderState === "recording");

    lastResult.textContent = values["last.result"] || (enabled ? (running ? "Daemon ready" : "Daemon stopped") : "Recording disabled");
    lastOutput.textContent = values["last.output"] || values["output.dir"] || "/sdcard/Recordings/BCR";

    const ordered = Object.keys(values)
        .sort()
        .map((key) => `${key}=${values[key]}`)
        .join("\n");
    statusOutput.textContent = ordered || "No status available.";
    updateDebugControls();
}

async function refreshStatus() {
    const raw = await run("sh ./action.sh status");
    updateUiFromStatus(parseStatus(raw));
}

function formatTimestamp(value) {
    if (!value) {
        return "Unknown time";
    }

    const parsed = new Date(value);
    if (Number.isNaN(parsed.getTime())) {
        return value;
    }

    return timestampFormatter.format(parsed);
}

function formatDuration(value) {
    if (typeof value !== "number" || Number.isNaN(value)) {
        return "Unknown length";
    }

    const totalSeconds = Math.max(0, Math.round(value));
    const hours = String(Math.floor(totalSeconds / 3600)).padStart(2, "0");
    const minutes = String(Math.floor((totalSeconds % 3600) / 60)).padStart(2, "0");
    const seconds = String(totalSeconds % 60).padStart(2, "0");
    return `${hours}:${minutes}:${seconds}`;
}

function formatDirection(value) {
    switch (value) {
        case "incoming":
            return "Incoming";
        case "outgoing":
            return "Outgoing";
        case "conference":
            return "Conference";
        default:
            return "Unknown direction";
    }
}

function basename(value) {
    if (!value) {
        return "No file saved";
    }

    const parts = String(value).split(/[\\/]/);
    return parts[parts.length - 1] || value;
}

function renderRecordingLog(entries) {
    recordingLogList.textContent = "";

    if (!entries.length) {
        const empty = document.createElement("p");
        empty.className = "entry-empty";
        empty.textContent = "No recorded calls yet.";
        recordingLogList.appendChild(empty);
        return;
    }

    for (const entry of entries) {
        const card = document.createElement("article");
        card.className = "entry-card";

        const head = document.createElement("div");
        head.className = "entry-head";

        const titleWrap = document.createElement("div");

        const title = document.createElement("strong");
        title.textContent = entry.phoneNumber || "Unknown number";
        titleWrap.appendChild(title);

        const subtitle = document.createElement("div");
        subtitle.className = "entry-meta";
        subtitle.textContent = formatTimestamp(entry.timestamp);
        titleWrap.appendChild(subtitle);

        const direction = document.createElement("div");
        direction.className = "entry-meta secondary";
        direction.textContent = formatDirection(entry.direction);
        titleWrap.appendChild(direction);

        const status = document.createElement("span");
        status.className = `entry-status ${entry.status}`;
        status.textContent = entry.status || "unknown";

        head.appendChild(titleWrap);
        head.appendChild(status);

        const details = document.createElement("div");
        details.className = "entry-details";
        details.innerHTML = `
            <div><span class="label">Duration</span><strong>${formatDuration(entry.durationSeconds)}</strong></div>
            <div><span class="label">Channels</span><strong>${entry.audioChannels || "Unknown"}</strong></div>
            <div><span class="label">File</span><strong>${basename(entry.outputFile)}</strong></div>
            <div><span class="label">Path</span><strong>${entry.outputFile || "No file saved"}</strong></div>
            <div><span class="label">Error</span><strong>${entry.error || "None"}</strong></div>
        `;

        card.appendChild(head);
        card.appendChild(details);

        if (entry.outputFile) {
            card.classList.add("clickable");
            card.addEventListener("click", async () => {
                await openRecording(entry.outputFile);
            });
        }

        recordingLogList.appendChild(card);
    }
}

async function refreshRecordingLog() {
    const raw = await run("sh ./action.sh recording-log list");
    let entries = [];

    try {
        entries = JSON.parse(raw || "[]");
    } catch (error) {
        recordingLogList.textContent = String(error.message || error);
        return;
    }

    renderRecordingLog(entries);
}

async function refreshAll() {
    await refreshStatus();
    await refreshRecordingLog();
    await refreshTranscriberComponentsStatus();
    await refreshTranscriberStatus();
    await refreshTranscriberRecordings();
}

async function openLastOutputTarget() {
    const lastOutputPath = latestStatus["last.output"]?.trim();
    const outputDirectory = latestStatus["output.dir"] || outputDir.value.trim() || "/sdcard/Recordings/BCR";

    if (!lastOutputPath) {
        await openOutputDirectory();
        return;
    }

    const normalize = (value) => String(value).replace(/\\/g, "/");
    if (normalize(lastOutputPath) === normalize(outputDirectory)) {
        await openOutputDirectory();
        return;
    }

    await openRecording(lastOutputPath);
}

async function saveConfigAndRestart() {
    const enabled = recordingEnabled.checked ? "1" : "0";
    const logEnabled = recordingLogEnabled.checked ? "1" : "0";
    const stereoEnabled = recordingMode?.value === PREPARE_PROFILE_MONO ? "0" : "1";
    const output = outputDir.value.trim() || "/sdcard/Recordings/BCR";
    const duration = String(Math.max(0, Number.parseInt(minDuration.value || "0", 10) || 0));

    await run(
        [
            `sh ./action.sh config set recording.enabled ${enabled}`,
            `sh ./action.sh config set recording.log_enabled ${logEnabled}`,
            `sh ./action.sh config set recording.stereo ${stereoEnabled}`,
            `sh ./action.sh config set output.dir ${shellQuote(output)}`,
            `sh ./action.sh config set recording.min_duration ${duration}`,
            "sh ./action.sh restart",
        ].join(" && "),
    );

    toast("Configuration applied");
    await refreshAll();
}

async function resetDefaults() {
    await run("sh ./action.sh reset-config && sh ./action.sh restart");
    toast("Defaults restored");
    await refreshAll();
}

async function openOutputDirectory() {
    const output = await run("sh ./action.sh open-output-dir");
    toast(output || "Opening output folder");
}

async function openRecording(path) {
    const output = await run(`sh ./action.sh open-recording ${shellQuote(path)}`);

    if (output.startsWith("open_recording.missing=")) {
        toast("Recording file is missing");
    } else if (output.startsWith("open_recording.unavailable=")) {
        toast(output);
    } else {
        toast(output || "Opening recording");
    }
}

async function clearRecordingLog() {
    await run("sh ./action.sh recording-log clear");
    toast("Recorded call log cleared");
    await refreshRecordingLog();
}

async function refreshTranscriberStatus() {
    const raw = await run("sh ./action.sh transcriber status");
    try {
        latestTranscriberStatus = JSON.parse(raw || "{}");
    } catch (error) {
        rememberDebugError("Transcriber status JSON parse failed", `${String(error.message || error)}\n${raw}`);
        throw error;
    }
    renderTranscriberStatus(latestTranscriberStatus);
    updateTranscriberPolling();
}

async function refreshTranscriberComponentsStatus() {
    const raw = await run("sh ./action.sh transcriber components-status");
    latestComponentsStatus = parseStatus(raw);
    renderTranscriberComponents(latestComponentsStatus);
    updateTranscriberPolling();
}

async function refreshTranscriberRecordings() {
    const raw = await run("sh ./action.sh transcriber list");
    latestRecordingCandidates = JSON.parse(raw || "[]");
    renderTranscriberRecordings(latestRecordingCandidates);
}

async function refreshTranscriberAll() {
    await refreshStatus();
    await refreshTranscriberStatus();
    await refreshTranscriberComponentsStatus();
    await refreshTranscriberRecordings();
}

async function refreshTranscriberComponentMetadata({ silent = true } = {}) {
    if (latestComponentsStatus["transcriber.components.running"] === "1") {
        return;
    }

    const result = await runCapture("sh ./action.sh transcriber components-refresh-metadata");
    if (!result.ok) {
        if (silent) {
            rememberDebugError("Transcriber component metadata refresh failed", result.stderr || result.stdout || "Unknown error");
            return;
        }
        throw new Error(result.stderr || result.stdout || "Unable to refresh component metadata");
    }

    latestComponentsStatus = parseStatus(result.stdout || "");
    renderTranscriberComponents(latestComponentsStatus);
    updateTranscriberWhisperLocalStatus(latestStatus, latestComponentsStatus);
    updateTranscriberPolling();
}

function renderTranscriberStatus(status) {
    const deps = status.dependencies || {};
    const queue = status.queue?.jobs || [];
    const runtime = status.runtime || {};
    const active = queue.filter((job) => job.status === "queued" || job.status === "running");
    const done = queue.filter((job) => job.status === "succeeded" || job.status === "skipped").length;
    const failed = queue.filter((job) => job.status === "failed" || job.status === "cancelled").length;
    const enabled = latestStatus["transcriber.enabled"] === "1";
    const recorderMode = getSavedRecorderMode(latestStatus);
    const ready = isTranscriberReadyForRecorderMode(status, latestStatus);
    const readyLabel = recorderMode === PREPARE_PROFILE_MONO ? "Ready for mono fallback" : "Ready for stereo";
    const needsLabel = recorderMode === PREPARE_PROFILE_MONO ? "Needs mono fallback components" : "Needs stereo components";

    transcriberEngineState.textContent = enabled
        ? (ready ? readyLabel : needsLabel)
        : "Disabled";
    transcriberQueueState.textContent = queue.length
        ? `${active.length} active, ${done} done, ${failed} failed`
        : "No jobs";

    transcriberWhisperPath.textContent = `${deps.whisperPath || "Not set"} (${deps.whisperExists ? "found" : "missing"})`;
    transcriberModelPath.textContent = `${deps.modelPath || "Not set"} (${deps.modelExists ? "found" : "missing"})`;
    transcriberTdrzPath.textContent = `${deps.tinydiarizeModelPath || "Not set"} (${deps.tinydiarizeModelExists ? "found" : "missing"})`;

    const currentProgress = Math.max(0, Math.min(100, Number(runtime.progress || 0)));
    transcriberCurrentProgress.style.width = `${currentProgress}%`;
    transcriberCurrentProgressLabel.textContent = runtime.state === "running" ? `${currentProgress}%` : runtime.state || "idle";

    const total = queue.length;
    const completeScore = queue.reduce((sum, job) => {
        if (job.status === "succeeded" || job.status === "skipped") {
            return sum + 100;
        }
        if (job.status === "running") {
            return sum + (Number(job.progress) || 0);
        }
        return sum;
    }, 0);
    const allProgress = total ? Math.round(completeScore / total) : 0;
    transcriberAllProgress.style.width = `${allProgress}%`;
    transcriberAllProgressLabel.textContent = runtime.etaSeconds
        ? `~${formatEta(runtime.etaSeconds)}`
        : (total ? `${allProgress}%` : "No estimate");

    renderTranscriberQueue(queue);
}

function renderTranscriberComponents(values) {
    renderComponent(
        values,
        "whisper_cli",
        transcriberWhisperPath,
        componentWhisperProgress,
        componentWhisperStatus,
    );
    renderComponent(
        values,
        "base_model",
        transcriberModelPath,
        componentModelProgress,
        componentModelStatus,
    );
    renderComponent(
        values,
        "tinydiarize_model",
        transcriberTdrzPath,
        componentTdrzProgress,
        componentTdrzStatus,
    );
    updateTranscriberWhisperLocalStatus(latestStatus, values);
}

function renderComponent(values, key, pathEl, progressEl, statusEl) {
    const prefix = `component.${key}.`;
    const status = values[`${prefix}status`] || "missing";
    const progress = Math.max(0, Math.min(100, Number(values[`${prefix}progress`] || 0)));
    const downloaded = Number(values[`${prefix}bytes_downloaded`] || 0);
    const total = Number(values[`${prefix}bytes_total`] || 0);
    const error = values[`${prefix}error`] || "";
    const note = values[`${prefix}note`] || "";
    const path = values[`${prefix}path`] || "Not set";
    const abi = values[`${prefix}abi`] || "";
    const build = values[`${prefix}build`] || "";
    const whisperRef = values[`${prefix}whisper_ref`] || "";
    const detail = [status, abi, build, whisperRef].filter(Boolean).join(", ");
    const shownProgress = status === "ready" ? 100 : progress;
    const source = formatComponentSource(values, prefix);
    const progressWrap = progressEl?.parentElement;
    const hideDetails = (status === "ready" || status === "optional") && !error;

    pathEl.textContent = `${path} (${detail})`;
    progressEl.style.width = `${shownProgress}%`;
    if (progressWrap) {
        progressWrap.hidden = hideDetails || status === "optional";
    }
    statusEl.textContent = hideDetails
        ? (status === "ready" ? "Installed and ready" : note || "Optional for this preparation mode")
        : error
            ? `${status}: ${error} • ${source}`
            : note && status === "optional"
                ? `${note} • ${source}`
                : `${status} • ${formatBytes(downloaded)} / ${formatBytes(total)} • ${shownProgress}% • ${source}`;
}

function formatComponentSource(values, prefix) {
    const sourceKind = values[`${prefix}source_kind`] || "";
    const sourceDetail = values[`${prefix}source_detail`] || "";
    const url = values[`${prefix}url`] || "";
    const manifestUrl = values[`${prefix}manifest_url`] || "";
    const localPath = values[`${prefix}local_path`] || "";

    if (sourceKind === "local") {
        return `Local package • ${basename(sourceDetail || localPath || "whisper-cli")}`;
    }
    if (sourceKind === "manifest") {
        if (sourceDetail && manifestUrl && sourceDetail !== manifestUrl) {
            return `ABI auto-select • ${basename(sourceDetail)} via manifest`;
        }
        return `ABI auto-select • ${manifestUrl || "Manifest not set"}`;
    }
    if (sourceKind === "url") {
        return `Direct URL • ${sourceDetail || url || "Not set"}`;
    }

    return sourceDetail || localPath || url || manifestUrl || "No source configured";
}

function updateTranscriberWhisperLocalStatus(values = latestStatus, components = latestComponentsStatus) {
    if (!transcriberWhisperLocalStatus) {
        return;
    }

    const typedPath = transcriberWhisperLocalPath?.value.trim() || "";
    const savedPath = values["transcriber.whisper_local_path"] || components["component.whisper_cli.local_path"] || "";
    if (!transcriberWhisperLocalEnabled?.checked && !savedPath) {
        transcriberWhisperLocalStatus.textContent =
            "Optional. Leave this off to use the automatic device-matched whisper.cpp CLI download.";
        return;
    }

    if (typedPath && typedPath !== savedPath) {
        transcriberWhisperLocalStatus.textContent =
            `${typedPath} • save transcriber settings to use this package`;
        return;
    }

    const localPath = savedPath;
    const componentStatus = components["component.whisper_cli.status"] || "missing";
    const componentError = components["component.whisper_cli.error"] || "";
    const componentSize = Number(components["component.whisper_cli.bytes_total"] || 0);

    if (!localPath) {
        transcriberWhisperLocalStatus.textContent =
            "Optional. Enter a full path if you want to use your own compiled whisper.cpp CLI package.";
        return;
    }

    const stateText = componentError
        ? componentError
        : componentStatus === "selected"
            ? "ready to install"
            : componentStatus === "ready"
                ? "installed source"
                : "local source selected";
    const sizeText = componentSize > 0 ? ` • ${formatBytes(componentSize)}` : "";
    transcriberWhisperLocalStatus.textContent = `${localPath}${sizeText} • ${stateText}`;
}

function renderTranscriberRecordings(recordings) {
    transcriberRecordingList.textContent = "";

    if (!recordings.length) {
        const empty = document.createElement("p");
        empty.className = "entry-empty";
        empty.textContent = "No recording files found.";
        transcriberRecordingList.appendChild(empty);
        return;
    }

    for (const recording of recordings) {
        const card = document.createElement("label");
        card.className = "entry-card";

        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.value = recording.path;
        checkbox.dataset.conflict = recording.selectedTranscriptExists ? "1" : "0";
        card.appendChild(checkbox);

        const body = document.createElement("div");
        const title = document.createElement("strong");
        title.textContent = recording.name;
        body.appendChild(title);

        const meta = document.createElement("div");
        meta.className = "entry-meta";
        const channelText = recording.audioChannels ? `${recording.audioChannels} channel` : "unknown channels";
        meta.textContent = `${formatBytes(recording.sizeBytes)} • ${channelText} • ${formatTimestamp(recording.modifiedAt)}`;
        body.appendChild(meta);

        const detail = document.createElement("div");
        detail.className = "entry-meta secondary";
        detail.textContent = recording.selectedTranscriptExists ? "Transcript exists" : "No matching transcript";
        body.appendChild(detail);

        card.appendChild(body);
        transcriberRecordingList.appendChild(card);
    }
}

function renderTranscriberQueue(jobs) {
    transcriberQueueList.textContent = "";

    if (!jobs.length) {
        const empty = document.createElement("p");
        empty.className = "entry-empty";
        empty.textContent = "No queued transcription jobs.";
        transcriberQueueList.appendChild(empty);
        return;
    }

    for (const job of jobs) {
        const card = document.createElement("article");
        card.className = "entry-card";

        const head = document.createElement("div");
        head.className = "entry-head";

        const titleWrap = document.createElement("div");
        const title = document.createElement("strong");
        title.textContent = basename(job.recordingPath);
        titleWrap.appendChild(title);

        const meta = document.createElement("div");
        meta.className = "entry-meta";
        meta.textContent = `${job.language || "en"} • ${job.format || "txt"} • ${job.diarizationMode || "pending"}`;
        titleWrap.appendChild(meta);

        const status = document.createElement("span");
        status.className = `entry-status ${job.status}`;
        status.textContent = job.status || "unknown";

        head.appendChild(titleWrap);
        head.appendChild(status);
        card.appendChild(head);

        const details = document.createElement("div");
        details.className = "entry-details";
        details.innerHTML = `
            <div><span class="label">Progress</span><strong>${Number(job.progress || 0)}%</strong></div>
            <div><span class="label">Started</span><strong>${job.startedAt ? formatTimestamp(job.startedAt) : "Not started yet"}</strong></div>
            <div><span class="label">Completed</span><strong>${job.completedAt ? formatTimestamp(job.completedAt) : "Not finished yet"}</strong></div>
            <div><span class="label">Transcript</span><strong>${job.transcriptPath || "Not written"}</strong></div>
            <div><span class="label">Error</span><strong>${job.error || "None"}</strong></div>
        `;
        card.appendChild(details);

        const actions = document.createElement("div");
        actions.className = "entry-actions";

        if (job.transcriptPath && (job.status === "succeeded" || job.status === "skipped")) {
            const open = document.createElement("button");
            open.className = "ghost";
            open.type = "button";
            open.textContent = "Open";
            open.addEventListener("click", async () => {
                await openTranscript(job.transcriptPath);
            });
            actions.appendChild(open);
        }

        if (job.status === "queued") {
            const remove = document.createElement("button");
            remove.className = "ghost danger";
            remove.type = "button";
            remove.textContent = "Remove";
            remove.addEventListener("click", async () => {
                await removeTranscriberJob(job.id);
            });
            actions.appendChild(remove);
        }

        if (actions.childElementCount) {
            card.appendChild(actions);
        }

        transcriberQueueList.appendChild(card);
    }
}

async function saveTranscriberConfig() {
    const wasEnabled = latestStatus["transcriber.enabled"] === "1";
    const willEnable = transcriberEnabled.checked;
    const output = transcriberOutputDir.value.trim() || `${outputDir.value.trim() || "/sdcard/Recordings/BCR"}/transcripts`;
    const language = transcriberLanguage.value || "en";
    const speakerSelfName = transcriberSpeakerSelfName.value.trim() || "Speaker A";
    const format = transcriberOutputFormat.value || "txt";
    const whisperManifestUrl = latestStatus["transcriber.whisper_manifest_url"] || DEFAULT_WHISPER_MANIFEST_URL;
    const whisperLocalPath = transcriberWhisperLocalEnabled.checked
        ? transcriberWhisperLocalPath.value.trim()
        : "";
    const modelUrl = transcriberModelUrlEnabled.checked
        ? transcriberModelUrl.value.trim()
        : (transcriberModelPreset.value || "");
    const tdrzUrl = transcriberTdrzUrlEnabled.checked
        ? transcriberTdrzUrl.value.trim()
        : (transcriberTdrzPreset.value || "");
    const autoQueue = transcriberAutoQueueEnabled.checked ? "1" : "0";
    const autoQueueRequireCharging = (transcriberAutoQueueEnabled.checked && transcriberAutoQueueChargingOnly.checked) ? "1" : "0";
    const autoQueueChargeDelaySeconds = String(Math.max(0, Number.parseInt(transcriberAutoQueueDelaySeconds.value || "30", 10) || 0));

    if (transcriberWhisperLocalEnabled.checked && !whisperLocalPath) {
        throw new Error("Enter a local whisper.cpp CLI package path or turn off the local package override.");
    }
    if (transcriberModelUrlEnabled.checked && !modelUrl) {
        throw new Error("Enter a custom Whisper model URL or turn off the custom Whisper model override.");
    }
    if (transcriberTdrzUrlEnabled.checked && !tdrzUrl) {
        throw new Error("Enter a custom TinyDiarize model URL or turn off the custom TinyDiarize override.");
    }

    if (!wasEnabled && willEnable) {
        const confirmed = await requestChoice({
            title: "Enable Transcriber",
            message: "This enables offline transcription using a module-local whisper.cpp CLI and offline models. Use Prepare Components to download either the stereo set or the mono fallback set before queueing jobs.",
            actions: [
                { label: "Enable", value: "enable", className: "" },
                { label: "Cancel", value: "cancel", className: "ghost" },
            ],
        });
        if (confirmed.value !== "enable") {
            transcriberEnabled.checked = false;
            return;
        }
    }

    if (wasEnabled && !willEnable) {
        const choice = await requestChoice({
            title: "Disable Transcriber",
            message: "Disable the transcription queue and keep existing transcripts.",
            checkboxLabel: "Also remove downloaded transcriber components",
            actions: [
                { label: "Disable", value: "disable", className: "danger" },
                { label: "Cancel", value: "cancel", className: "ghost" },
            ],
        });
        if (choice.value !== "disable") {
            transcriberEnabled.checked = true;
            return;
        }
        if (choice.checked) {
            await run("sh ./action.sh transcriber remove-deps");
        }
    }

    await run(
        [
            `sh ./action.sh config set transcriber.enabled ${willEnable ? "1" : "0"}`,
            `sh ./action.sh config set transcriber.output_dir ${shellQuote(output)}`,
            `sh ./action.sh config set transcriber.language ${shellQuote(language)}`,
            `sh ./action.sh config set transcriber.speaker_self_name ${shellQuote(speakerSelfName)}`,
            `sh ./action.sh config set transcriber.output_format ${shellQuote(format)}`,
            `sh ./action.sh config set transcriber.auto_queue ${autoQueue}`,
            `sh ./action.sh config set transcriber.auto_queue_require_charging ${autoQueueRequireCharging}`,
            `sh ./action.sh config set transcriber.auto_queue_charge_delay_seconds ${autoQueueChargeDelaySeconds}`,
            `sh ./action.sh config set transcriber.whisper_manifest_url ${shellQuote(whisperManifestUrl)}`,
            `sh ./action.sh config set transcriber.whisper_url ''`,
            `sh ./action.sh config set transcriber.whisper_local_path ${shellQuote(whisperLocalPath)}`,
            `sh ./action.sh config set transcriber.model_url ${shellQuote(modelUrl)}`,
            `sh ./action.sh config set transcriber.tinydiarize_model_url ${shellQuote(tdrzUrl)}`,
        ].join(" && "),
    );
    await run("sh ./action.sh transcriber components-reset");

    if (willEnable) {
        await run("sh ./action.sh transcriber start-worker");
    }

    toast("Transcriber settings saved");
    await refreshTranscriberAll();
}

async function queueSelectedRecordings() {
    const selected = Array.from(transcriberRecordingList.querySelectorAll("input[type='checkbox']:checked"));
    if (!selected.length) {
        toast("Select one or more recordings");
        return;
    }

    let conflictPolicy = "cancel";
    if (selected.some((checkbox) => checkbox.dataset.conflict === "1")) {
        const choice = await requestChoice({
            title: "Transcript Exists",
            message: "One or more selected recordings already have a matching transcript.",
            actions: [
                { label: "Skip Existing", value: "skip", className: "" },
                { label: "Overwrite", value: "overwrite", className: "ghost" },
                { label: "Cancel", value: "cancel", className: "ghost danger" },
            ],
        });
        conflictPolicy = choice.value;
        if (conflictPolicy === "cancel") {
            return;
        }
    } else {
        conflictPolicy = "skip";
    }

    const paths = selected.map((checkbox) => shellQuote(checkbox.value)).join(" ");
    await run(`sh ./action.sh transcriber enqueue ${conflictPolicy} ${paths}`);
    toast("Transcription job queued");
    await refreshTranscriberAll();
}

async function transcriberControl(command) {
    await run(`sh ./action.sh transcriber ${command}`);
    await refreshTranscriberStatus();
}

async function removeTranscriberJob(id) {
    await run(`sh ./action.sh transcriber remove ${shellQuote(id)}`);
    await refreshTranscriberStatus();
}

async function openTranscript(path) {
    const output = await run(`sh ./action.sh transcriber open-transcript ${shellQuote(path)}`);
    toast(output || "Opening transcript");
}

function selectedRecordingCheckboxes() {
    return Array.from(transcriberRecordingList.querySelectorAll("input[type='checkbox']"));
}

function isDebugEnabled() {
    return debugEnabled ? debugEnabled.checked : latestStatus["debug.enabled"] === "1";
}

function setDebugOutputs(message) {
    for (const output of [
        debugTranscriberStatusOutput,
        debugComponentsOutput,
        debugJobsOutput,
        debugTranscriberLogsOutput,
    ]) {
        if (output) {
            output.textContent = message;
        }
    }
}

function formatCapturedCommand(result) {
    const parts = [
        `$ ${result.command}`,
        `exit=${result.errno}`,
    ];
    if (result.stdout) {
        parts.push("stdout:", result.stdout);
    }
    if (result.stderr) {
        parts.push("stderr:", result.stderr);
    }
    if (!result.stdout && !result.stderr) {
        parts.push("(no output)");
    }
    return parts.join("\n");
}

function renderJobsDebug(status) {
    const queue = status.queue?.jobs || [];
    const runtime = status.runtime || {};
    const counts = queue.reduce((acc, job) => {
        const key = job.status || "unknown";
        acc[key] = (acc[key] || 0) + 1;
        return acc;
    }, {});
    const lines = [
        `runtime.state=${runtime.state || "unknown"}`,
        `runtime.progress=${runtime.progress ?? 0}`,
        `runtime.etaSeconds=${runtime.etaSeconds ?? ""}`,
        `runtime.error=${runtime.error || ""}`,
        `queue.total=${queue.length}`,
        `queue.counts=${JSON.stringify(counts)}`,
        "",
    ];

    if (!queue.length) {
        lines.push("No queued transcription jobs.");
        return lines.join("\n");
    }

    for (const job of queue) {
        lines.push([
            job.id || "unknown-id",
            job.status || "unknown",
            `${job.progress ?? 0}%`,
            job.startedAt ? `started=${job.startedAt}` : "",
            job.completedAt ? `completed=${job.completedAt}` : "",
            job.error ? `error=${job.error}` : "",
            job.recordingPath || job.inputPath || "",
            job.transcriptPath || job.outputPath || "",
        ].filter(Boolean).join(" | "));
    }

    return lines.join("\n");
}

function rememberDebugError(title, error) {
    if (!isDebugEnabled() || !debugTranscriberStatusOutput) {
        return;
    }

    const detail = error instanceof Error ? error.message : String(error);
    const timestamp = timestampFormatter.format(new Date());
    const previous = debugTranscriberStatusOutput.textContent || "";
    debugTranscriberStatusOutput.textContent = [
        `[${timestamp}] ${title}`,
        detail,
        previous && previous !== "Debug tracking is disabled." ? `\n${previous}` : "",
    ].filter(Boolean).join("\n");
}

function updateDebugControls() {
    const enabled = isDebugEnabled();
    if (debugStatusBadge) {
        debugStatusBadge.textContent = enabled ? "On" : "Off";
        debugStatusBadge.classList.toggle("recording", enabled);
    }

    for (const selector of [
        "#refresh-button",
        "#restart-button",
        "#probe-button",
        "#logs-button",
        "#refresh-transcriber-debug-button",
    ]) {
        const button = document.querySelector(selector);
        if (button) {
            button.disabled = !enabled;
        }
    }

    if (!enabled) {
        setDebugOutputs("Debug tracking is disabled.");
    } else if (activeTabName === "debug" && debugTranscriberStatusOutput?.textContent === "Debug tracking is disabled.") {
        setDebugOutputs("Debug tracking is enabled. Use Refresh Transcriber Debug when needed.");
    }
}

async function refreshTranscriberDebug() {
    if (!isDebugEnabled()) {
        setDebugOutputs("Debug tracking is disabled.");
        return;
    }

    debugComponentsOutput.textContent = "Refreshing component status...";
    const components = await runCapture("sh ./action.sh transcriber components-status");
    debugComponentsOutput.textContent = formatCapturedCommand(components);

    if (components.ok) {
        latestComponentsStatus = parseStatus(components.stdout);
        renderTranscriberComponents(latestComponentsStatus);
    }

    debugTranscriberStatusOutput.textContent = "Refreshing transcriber status...";
    const status = await runCapture("sh ./action.sh transcriber status");
    if (!status.ok) {
        debugTranscriberStatusOutput.textContent = formatCapturedCommand(status);
        debugJobsOutput.textContent = "Transcriber status command failed; jobs could not be read.";
    } else {
        try {
            const parsed = JSON.parse(status.stdout || "{}");
            latestTranscriberStatus = parsed;
            debugTranscriberStatusOutput.textContent = JSON.stringify(parsed, null, 2);
            debugJobsOutput.textContent = renderJobsDebug(parsed);
        } catch (error) {
            debugTranscriberStatusOutput.textContent = [
                String(error.message || error),
                "",
                formatCapturedCommand(status),
            ].join("\n");
            debugJobsOutput.textContent = "Transcriber status JSON could not be parsed.";
        }
    }

    debugTranscriberLogsOutput.textContent = "Refreshing transcriber logs...";
    const logs = await runCapture("sh ./action.sh transcriber logs");
    debugTranscriberLogsOutput.textContent = logs.stdout || logs.stderr || "No transcriber logs yet.";
}

async function saveDebugConfig() {
    await run(`sh ./action.sh config set debug.enabled ${debugEnabled.checked ? "1" : "0"}`);
    await refreshStatus();
    updateDebugControls();
    if (isDebugEnabled()) {
        setDebugOutputs("Debug tracking is enabled. Use Refresh Transcriber Debug when needed.");
        toast("Debug tracking enabled");
    } else {
        setDebugOutputs("Debug tracking is disabled.");
        toast("Debug tracking disabled");
    }
}

function formatBytes(value) {
    const size = Number(value || 0);
    if (size < 1024) {
        return `${size} B`;
    }
    if (size < 1024 * 1024) {
        return `${(size / 1024).toFixed(1)} KB`;
    }
    return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

function formatEta(seconds) {
    const total = Math.max(0, Number(seconds) || 0);
    const mins = Math.floor(total / 60);
    const secs = Math.round(total % 60);
    if (mins <= 0) {
        return `${secs}s`;
    }
    return `${mins}m ${secs}s`;
}

function transcriberHasActiveJobs(status = latestTranscriberStatus) {
    const jobs = status?.queue?.jobs || [];
    return jobs.some((job) => job.status === "queued" || job.status === "running");
}

function transcriberRuntimeNeedsPolling(status = latestTranscriberStatus) {
    const runtimeState = status?.runtime?.state || "";
    return ["running", "resuming", "paused", "stopping"].includes(runtimeState);
}

function shouldPollTranscriber() {
    if (activeTabName !== "transcriber") {
        return false;
    }

    return latestComponentsStatus["transcriber.components.running"] === "1" ||
        transcriberHasActiveJobs() ||
        transcriberRuntimeNeedsPolling();
}

function updateTranscriberPolling() {
    if (shouldPollTranscriber()) {
        startComponentPolling();
    } else {
        stopComponentPolling();
    }
}

function startComponentPolling() {
    if (componentPollTimer || activeTabName !== "transcriber") {
        return;
    }

    componentPollTimer = window.setInterval(async () => {
        if (componentPollInFlight) {
            return;
        }

        if (!shouldPollTranscriber()) {
            stopComponentPolling();
            return;
        }

        componentPollInFlight = true;
        try {
            await refreshTranscriberStatus();
            await refreshTranscriberComponentsStatus();
        } catch (error) {
            stopComponentPolling();
            rememberDebugError("Component status refresh failed", error);
        } finally {
            componentPollInFlight = false;
        }
    }, 1500);
}

function stopComponentPolling() {
    if (!componentPollTimer) {
        return;
    }

    window.clearInterval(componentPollTimer);
    componentPollTimer = null;
    componentPollInFlight = false;
}

function closeConfirmOverlay(confirmed) {
    if (confirmOverlay) {
        confirmOverlay.hidden = true;
        confirmOverlay.classList.remove("open");
    }

    if (confirmResolver) {
        const resolver = confirmResolver;
        confirmResolver = null;
        resolver(confirmed);
    }
}

function requestClearLogConfirmation() {
    if (!confirmOverlay) {
        return Promise.resolve(true);
    }

    confirmOverlay.hidden = false;
    confirmOverlay.classList.add("open");

    return new Promise((resolve) => {
        confirmResolver = resolve;
    });
}

function closeChoiceOverlay(result) {
    if (choiceOverlay) {
        choiceOverlay.hidden = true;
        choiceOverlay.classList.remove("open");
    }

    if (choiceResolver) {
        const resolver = choiceResolver;
        choiceResolver = null;
        resolver(result);
    }
}

function requestChoice({ title, message, checkboxLabel = null, actions }) {
    if (!choiceOverlay) {
        return Promise.resolve({ value: actions[0]?.value, checked: false });
    }

    choiceTitle.textContent = title;
    choiceMessage.textContent = message;
    choiceActions.textContent = "";
    choiceCheckbox.checked = false;

    if (checkboxLabel) {
        choiceCheckboxRow.hidden = false;
        choiceCheckboxLabel.textContent = checkboxLabel;
    } else {
        choiceCheckboxRow.hidden = true;
        choiceCheckboxLabel.textContent = "";
    }

    for (const action of actions) {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = action.label;
        if (action.className) {
            button.className = action.className;
        }
        button.addEventListener("click", () => {
            closeChoiceOverlay({
                value: action.value,
                checked: choiceCheckbox.checked,
            });
        });
        choiceActions.appendChild(button);
    }

    choiceOverlay.hidden = false;
    choiceOverlay.classList.add("open");

    return new Promise((resolve) => {
        choiceResolver = resolve;
    });
}

if (confirmOverlay) {
    // KSUWebUI hosts do not always respect the raw `hidden` attribute on first
    // paint, so force the modal into a closed state during startup.
    confirmOverlay.hidden = true;
    confirmOverlay.classList.remove("open");
}

if (choiceOverlay) {
    choiceOverlay.hidden = true;
    choiceOverlay.classList.remove("open");
}

async function runDiagnostic(command, emptyMessage) {
    const output = await run(`sh ./action.sh ${command}`);
    diagnosticOutput.textContent = output || emptyMessage;
}

recorderTab.addEventListener("click", () => setActiveTab("recorder"));
recordingsTab.addEventListener("click", async () => {
    setActiveTab("recordings");
    try {
        await refreshRecordingLog();
    } catch (error) {
        toast(String(error.message || error));
    }
});
transcriberTab.addEventListener("click", async () => {
    setActiveTab("transcriber");
    setBusy(true);
    try {
        await refreshTranscriberAll();
        await refreshTranscriberComponentMetadata({ silent: true });
    } catch (error) {
        rememberDebugError("Transcriber tab refresh failed", error);
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});
debugTab.addEventListener("click", async () => {
    setActiveTab("debug");
    if (isDebugEnabled() && debugTranscriberStatusOutput?.textContent === "Debug tracking is disabled.") {
        setDebugOutputs("Debug tracking is enabled. Use Refresh Transcriber Debug when needed.");
    }
});

document.querySelector("#refresh-status-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await refreshStatus();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

lastOutputTile?.addEventListener("click", async () => {
    setBusy(true);
    try {
        await openLastOutputTarget();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

lastOutputTile?.addEventListener("keydown", async (event) => {
    if (event.key !== "Enter" && event.key !== " ") {
        return;
    }

    event.preventDefault();
    setBusy(true);
    try {
        await openLastOutputTarget();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#save-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await saveConfigAndRestart();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#reset-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await resetDefaults();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#open-output-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await openOutputDirectory();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#refresh-log-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await refreshRecordingLog();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#clear-log-button").addEventListener("click", async () => {
    try {
        const confirmed = await requestClearLogConfirmation();
        if (!confirmed) {
            return;
        }

        setBusy(true);
        await clearRecordingLog();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#refresh-transcriber-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await refreshTranscriberAll();
        await refreshTranscriberComponentMetadata({ silent: true });
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#save-transcriber-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await saveTranscriberConfig();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

transcriberWhisperLocalPath?.addEventListener("input", () => {
    updateTranscriberWhisperLocalStatus();
});
recordingMode?.addEventListener("change", () => {
    updateRecordingModeNote(latestStatus);
});
transcriberWhisperLocalEnabled?.addEventListener("change", () => {
    syncTranscriberAdvancedFields();
    updateTranscriberWhisperLocalStatus();
});
transcriberModelUrlEnabled?.addEventListener("change", () => {
    syncTranscriberAdvancedFields();
});
transcriberTdrzUrlEnabled?.addEventListener("change", () => {
    syncTranscriberAdvancedFields();
});
transcriberAutoQueueEnabled?.addEventListener("change", () => {
    syncTranscriberAdvancedFields();
});
transcriberAutoQueueChargingOnly?.addEventListener("change", () => {
    syncTranscriberAdvancedFields();
});

document.querySelector("#install-transcriber-deps-button").addEventListener("click", async () => {
    try {
        await refreshTranscriberComponentMetadata({ silent: false });
    } catch (error) {
        toast(String(error.message || error));
        return;
    }

    const recorderMode = getSavedRecorderMode(latestStatus);
    const stereoEstimate = getPrepareEstimateBytes(PREPARE_PROFILE_STEREO, latestComponentsStatus);
    const monoEstimate = getPrepareEstimateBytes(PREPARE_PROFILE_MONO, latestComponentsStatus);
    const whisperModelLabel = transcriberModelUrlEnabled.checked
        ? "your custom Whisper model"
        : (transcriberModelPreset.selectedOptions[0]?.textContent || "the selected Whisper model");
    const tdrzModelLabel = transcriberTdrzUrlEnabled.checked
        ? "your custom TinyDiarize model"
        : (transcriberTdrzPreset.selectedOptions[0]?.textContent || "the selected TinyDiarize model");
    const choice = await requestChoice({
        title: "Prepare Components",
        message:
            `Choose which component set to prepare. ` +
            `Stereo downloads the ABI-matched whisper.cpp CLI package plus ${whisperModelLabel} ` +
            `(${formatBytes(stereoEstimate)}). ` +
            `Mono fallback downloads the ABI-matched whisper.cpp CLI package plus ${tdrzModelLabel} ` +
            `(${formatBytes(monoEstimate)}). ` +
            `Current recorder mode: ${getRecorderModeLabel(recorderMode)}.`,
        actions: [
            { label: "Stereo", value: PREPARE_PROFILE_STEREO, className: "" },
            { label: "Mono fallback", value: PREPARE_PROFILE_MONO, className: "ghost" },
            { label: "Cancel", value: "cancel", className: "ghost" },
        ],
    });
    if (![PREPARE_PROFILE_STEREO, PREPARE_PROFILE_MONO].includes(choice.value)) {
        return;
    }

    setBusy(true);
    try {
        const output = await run(`sh ./action.sh transcriber install-deps ${choice.value}`);
        toast(output || "Component download started");
        await refreshTranscriberComponentsStatus();
        updateTranscriberPolling();
    } catch (error) {
        rememberDebugError("Prepare Components failed", error);
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#remove-transcriber-deps-button").addEventListener("click", async () => {
    const choice = await requestChoice({
        title: "Remove Components",
        message: "Remove module-local whisper.cpp binaries, models, and temporary transcriber work files. Existing transcripts are kept.",
        actions: [
            { label: "Remove", value: "remove", className: "danger" },
            { label: "Cancel", value: "cancel", className: "ghost" },
        ],
    });
    if (choice.value !== "remove") {
        return;
    }

    setBusy(true);
    try {
        await run("sh ./action.sh transcriber remove-deps");
        toast("Transcriber components removed");
        await refreshTranscriberStatus();
        await refreshTranscriberComponentsStatus();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#refresh-recordings-for-transcriber-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await refreshTranscriberRecordings();
        await refreshTranscriberStatus();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#select-all-recordings-button").addEventListener("click", () => {
    for (const checkbox of selectedRecordingCheckboxes()) {
        checkbox.checked = true;
    }
});

document.querySelector("#clear-recording-selection-button").addEventListener("click", () => {
    for (const checkbox of selectedRecordingCheckboxes()) {
        checkbox.checked = false;
    }
});

document.querySelector("#queue-selected-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await queueSelectedRecordings();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#pause-transcriber-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await transcriberControl("pause");
        toast("Transcriber paused");
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#resume-transcriber-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await transcriberControl("resume");
        toast("Transcriber resumed");
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#stop-transcriber-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await transcriberControl("stop");
        toast("Transcriber stopping");
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#clear-transcriber-queue-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await transcriberControl("clear");
        toast("Completed jobs cleared");
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

confirmClearButton?.addEventListener("click", () => {
    closeConfirmOverlay(true);
});

cancelClearButton?.addEventListener("click", () => {
    closeConfirmOverlay(false);
});

confirmOverlay?.addEventListener("click", (event) => {
    if (event.target === confirmOverlay) {
        closeConfirmOverlay(false);
    }
});

choiceOverlay?.addEventListener("click", (event) => {
    if (event.target === choiceOverlay) {
        closeChoiceOverlay({ value: "cancel", checked: false });
    }
});

document.querySelector("#save-debug-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await saveDebugConfig();
    } catch (error) {
        rememberDebugError("Saving debug configuration failed", error);
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

debugEnabled?.addEventListener("change", () => {
    updateDebugControls();
});

document.querySelector("#refresh-transcriber-debug-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await refreshTranscriberDebug();
    } catch (error) {
        rememberDebugError("Manual transcriber debug refresh failed", error);
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#refresh-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        if (!isDebugEnabled()) {
            toast("Enable debug tracking first");
            return;
        }
        await refreshAll();
    } catch (error) {
        rememberDebugError("Debug refresh failed", error);
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#restart-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        if (!isDebugEnabled()) {
            toast("Enable debug tracking first");
            return;
        }
        await run("sh ./action.sh restart");
        await refreshAll();
        toast("Daemon restarted");
    } catch (error) {
        rememberDebugError("Daemon restart failed", error);
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#probe-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        if (!isDebugEnabled()) {
            toast("Enable debug tracking first");
            return;
        }
        await runDiagnostic("probe", "Probe returned no output.");
        await refreshStatus();
    } catch (error) {
        rememberDebugError("Probe failed", error);
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#logs-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        if (!isDebugEnabled()) {
            toast("Enable debug tracking first");
            return;
        }
        await runDiagnostic("logs", "No daemon logs yet.");
    } catch (error) {
        rememberDebugError("Reading daemon logs failed", error);
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

syncTranscriberAdvancedFields();
setActiveTab("recorder");
setBusy(true);
refreshAll()
    .catch((error) => {
        statusOutput.textContent = String(error.message || error);
        diagnosticOutput.textContent = String(error.message || error);
        recordingLogList.textContent = String(error.message || error);
        toast(String(error.message || error));
    })
    .finally(() => {
        setBusy(false);
    });

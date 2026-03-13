import { exec, moduleInfo, toast } from "./kernelsu.js";

const DEFAULT_MODULE_ID = "bcr.headless";

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
const runtimeBadge = document.querySelector("#runtime-badge");
const lastResult = document.querySelector("#last-result");
const lastOutput = document.querySelector("#last-output");
const lastOutputTile = document.querySelector("#last-output-tile");
const recordingEnabled = document.querySelector("#recording-enabled");
const recordingLogEnabled = document.querySelector("#recording-log-enabled");
const outputDir = document.querySelector("#output-dir");
const minDuration = document.querySelector("#min-duration");

const recorderView = document.querySelector("#recorder-view");
const recordingsView = document.querySelector("#recordings-view");
const debugView = document.querySelector("#debug-view");
const recorderTab = document.querySelector("#recorder-tab");
const recordingsTab = document.querySelector("#recordings-tab");
const debugTab = document.querySelector("#debug-tab");
const recordingLogList = document.querySelector("#recording-log-list");
const confirmOverlay = document.querySelector("#confirm-overlay");
const confirmClearButton = document.querySelector("#confirm-clear-button");
const cancelClearButton = document.querySelector("#cancel-clear-button");

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
let latestStatus = {};

function shellQuote(value) {
    return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

async function run(command) {
    const result = await exec(command, {
        cwd: MODULE.moduleDir,
        env: {
            KSU_MODULE: MODULE.moduleId,
        },
    });

    if (result.errno !== 0) {
        throw new Error(result.stderr || result.stdout || `Command failed with errno ${result.errno}`);
    }

    return result.stdout.trim();
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
}

function setActiveTab(name) {
    const isRecorder = name === "recorder";
    const isRecordings = name === "recordings";
    const isDebug = name === "debug";

    recorderView.hidden = !isRecorder;
    recordingsView.hidden = !isRecordings;
    debugView.hidden = !isDebug;

    recorderTab.classList.toggle("active", isRecorder);
    recorderTab.classList.toggle("ghost", !isRecorder);
    recordingsTab.classList.toggle("active", isRecordings);
    recordingsTab.classList.toggle("ghost", !isRecordings);
    debugTab.classList.toggle("active", isDebug);
    debugTab.classList.toggle("ghost", !isDebug);
}

function updateUiFromStatus(values) {
    latestStatus = values;
    const enabled = values["recording.enabled"] === "1";

    recordingEnabled.checked = enabled;
    recordingLogEnabled.checked = values["recording.log_enabled"] !== "0";
    outputDir.value = values["output.dir"] || "/sdcard/Recordings/BCR";
    minDuration.value = values["recording.min_duration"] || "0";

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
    const output = outputDir.value.trim() || "/sdcard/Recordings/BCR";
    const duration = String(Math.max(0, Number.parseInt(minDuration.value || "0", 10) || 0));

    await run(
        [
            `sh ./action.sh config set recording.enabled ${enabled}`,
            `sh ./action.sh config set recording.log_enabled ${logEnabled}`,
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

if (confirmOverlay) {
    // KSUWebUI hosts do not always respect the raw `hidden` attribute on first
    // paint, so force the modal into a closed state during startup.
    confirmOverlay.hidden = true;
    confirmOverlay.classList.remove("open");
}

async function runDiagnostic(command, emptyMessage) {
    const output = await run(`sh ./action.sh ${command}`);
    diagnosticOutput.textContent = output || emptyMessage;
}

recorderTab.addEventListener("click", () => setActiveTab("recorder"));
recordingsTab.addEventListener("click", () => setActiveTab("recordings"));
debugTab.addEventListener("click", () => setActiveTab("debug"));

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

document.querySelector("#refresh-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await refreshAll();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#restart-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await run("sh ./action.sh restart");
        await refreshAll();
        toast("Daemon restarted");
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#probe-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await runDiagnostic("probe", "Probe returned no output.");
        await refreshStatus();
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

document.querySelector("#logs-button").addEventListener("click", async () => {
    setBusy(true);
    try {
        await runDiagnostic("logs", "No daemon logs yet.");
    } catch (error) {
        toast(String(error.message || error));
    } finally {
        setBusy(false);
    }
});

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

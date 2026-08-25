import { getSnapshot, IS_MOCK, run, runCapture, shellQuote } from "./api.js";
import { appState, clearDirty, markDirty, rememberSelection, rememberView } from "./state.js";
import { badge, element, iconButton, renderIcons, requestChoice, setPending, showListState, showToast } from "./ui.js";

const DEFAULT_OUTPUT_DIR = "/sdcard/Recordings/BCRHeadless";
const DEFAULT_MANIFEST_URL = "https://github.com/wjdob/BCR-Headless/releases/download/transcriber-tools/transcriber-tools.env";
const DEFAULT_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin";
const DEFAULT_TINYDIARIZE_URL = "https://huggingface.co/akashmjn/tinydiarize-whisper.cpp/resolve/main/ggml-small.en-tdrz.bin";
const COMPONENT_KEYS = ["whisper_cli", "base_model", "tinydiarize_model"];
const VIEW_INFO = {
    recorder: { title: "Recorder", kicker: "Call recording" },
    library: { title: "Library", kicker: "Saved calls" },
    transcriber: { title: "Transcribe", kicker: "Offline processing" },
    diagnostics: { title: "Diagnostics", kicker: "Support tools" },
};
const LANGUAGES = [
    ["en", "English"], ["auto", "Auto detect"], ["es", "Spanish"], ["fr", "French"], ["de", "German"],
    ["it", "Italian"], ["pt", "Portuguese"], ["pl", "Polish"], ["nl", "Dutch"], ["tr", "Turkish"],
    ["cs", "Czech"], ["uk", "Ukrainian"], ["ru", "Russian"], ["ar", "Arabic"], ["hi", "Hindi"],
    ["ja", "Japanese"], ["ko", "Korean"], ["zh", "Chinese"],
];

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];
const dateFormatter = new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" });
const activeRefreshes = new Map();
let searchTimer = null;

function basename(path) {
    const parts = String(path || "").split(/[\\/]/);
    return parts.at(-1) || "Unknown file";
}

function formatBytes(value) {
    const bytes = Math.max(0, Number(value) || 0);
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
    return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
}

function formatDate(value, fallback = "Not started") {
    if (!value) return fallback;
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : dateFormatter.format(date);
}

function formatElapsed(start, end = Date.now()) {
    if (!start) return "Not started";
    const started = new Date(start).getTime();
    const finished = typeof end === "number" ? end : new Date(end).getTime();
    if (!Number.isFinite(started) || !Number.isFinite(finished)) return "Unknown";
    const seconds = Math.max(0, Math.round((finished - started) / 1000));
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    const remaining = seconds % 60;
    if (hours) return `${hours}h ${minutes}m`;
    if (minutes) return `${minutes}m ${remaining}s`;
    return `${remaining}s`;
}

function formatEta(seconds) {
    const total = Number(seconds);
    if (!Number.isFinite(total) || total <= 0) return "No active estimate";
    if (total < 60) return `About ${Math.ceil(total)} seconds remaining`;
    return `About ${Math.ceil(total / 60)} minutes remaining`;
}

function titleCase(value) {
    return String(value || "unknown").replace(/[_-]+/g, " ").replace(/\b\w/g, (char) => char.toUpperCase());
}

function formatRaw(values) {
    return Object.keys(values || {}).sort().map((key) => `${key}=${values[key]}`).join("\n") || "No data returned.";
}

function statusTone(status) {
    if (["ready", "running", "recording", "succeeded", "completed", "complete"].includes(status)) return "success";
    if (["failed", "error", "cancelled", "missing", "stopped"].includes(status)) return "danger";
    if (["queued", "paused", "preparing", "downloading", "stopping", "skipped"].includes(status)) return "warning";
    return "neutral";
}

function currentMode(status = appState.status) {
    return status["recording.stereo"] === "0" ? "mono" : "stereo";
}

function currentFilter() {
    return appState.activeView === "library" ? appState.filters.library : appState.filters.transcriber;
}

async function withPending(button, task, pendingLabel) {
    setPending(button, true, pendingLabel);
    try {
        return await task();
    } catch (error) {
        showActionError(error);
        return undefined;
    } finally {
        setPending(button, false);
    }
}

function showActionError(error) {
    const message = String(error?.message || error || "The action could not be completed.");
    $("#action-error-message").textContent = message;
    $("#action-error").hidden = false;
}

function dismissActionError() {
    $("#action-error").hidden = true;
    $("#action-error-message").textContent = "";
}

function setView(view, { refresh = true } = {}) {
    if (!VIEW_INFO[view]) view = "recorder";
    rememberView(view);
    for (const section of $$(".view")) section.hidden = section.id !== `${view === "transcriber" ? "transcribe" : view}-view`;
    for (const button of $$(`[data-view-target]`)) {
        const active = button.dataset.viewTarget === view;
        button.classList.toggle("is-active", active);
        if (button.getAttribute("role") === "tab") {
            button.setAttribute("aria-selected", String(active));
            button.tabIndex = active ? 0 : -1;
        } else if (active) {
            button.setAttribute("aria-current", "page");
        } else {
            button.removeAttribute("aria-current");
        }
    }
    $("#view-title").textContent = VIEW_INFO[view].title;
    $("#view-kicker").textContent = VIEW_INFO[view].kicker;
    stopPolling();
    if (refresh) refreshActiveView();
}

function showInitialLoading(view) {
    const target = view === "library" ? $("#library-list") : view === "transcriber" ? $("#queue-list") : null;
    if (target && !appState.latestSnapshots.has(view)) showListState(target, "Loading current module state...", "loading");
}

async function refreshActiveView({ forceForm = false, silent = false } = {}) {
    const view = appState.activeView;
    const filter = currentFilter();
    const requestKey = `${view}:${JSON.stringify(filter)}`;
    if (activeRefreshes.has(requestKey)) return activeRefreshes.get(requestKey);
    const serial = ++appState.requestSerial;
    if (!silent) showInitialLoading(view);
    const refreshButton = $("#refresh-view-button");
    setPending(refreshButton, true, "Refreshing");
    const request = getSnapshot(view, filter)
        .then((snapshot) => {
            if (serial !== appState.requestSerial || view !== appState.activeView) return;
            applySnapshot(view, snapshot, forceForm);
            appState.latestSnapshots.set(view, snapshot);
        })
        .catch((error) => {
            if (!silent) showActionError(error);
            const target = view === "library" ? $("#library-list") : view === "transcriber" ? $("#queue-list") : null;
            if (target) showListState(target, String(error.message || error), "error");
        })
        .finally(() => {
            activeRefreshes.delete(requestKey);
            setPending(refreshButton, false);
            updatePolling();
        });
    activeRefreshes.set(requestKey, request);
    return request;
}

function applySnapshot(view, snapshot, forceForm) {
    appState.status = snapshot.status || {};
    if (snapshot.transcriber) appState.transcriber = snapshot.transcriber;
    if (snapshot.components) appState.components = snapshot.components;
    if (snapshot.recordings) appState.recordings = snapshot.recordings;
    if (snapshot.recordingLog) appState.recordingLog = snapshot.recordingLog;
    renderGlobalStatus();
    if (forceForm || !appState.dirtySections.has("recorder")) populateRecorderForm();
    if (forceForm || !appState.dirtySections.has("transcriber")) populateTranscriberForm();
    if (view === "recorder") renderRecorder();
    if (view === "library") renderLibrary();
    if (view === "transcriber") renderTranscriber();
    if (view === "diagnostics") renderDiagnostics();
}

function renderGlobalStatus() {
    const status = appState.status;
    const running = status["daemon.running"] === "1";
    const enabled = status["recording.enabled"] === "1";
    const label = !enabled ? "Recording off" : running ? "Recorder ready" : "Daemon stopped";
    const pill = $("#global-status");
    pill.replaceChildren(element("span", { className: "status-dot" }), document.createTextNode(label));
    pill.className = `status-pill status-${!enabled ? "neutral" : running ? "success" : "danger"}`;
    $("#module-version").textContent = status["module.version"] || (IS_MOCK ? "1.3.0 mock" : "1.3.0");
}

function renderRecorder() {
    const status = appState.status;
    const running = status["daemon.running"] === "1";
    const enabled = status["recording.enabled"] === "1";
    $("#recorder-daemon-state").textContent = running ? "Running" : "Stopped";
    $("#recorder-runtime-state").textContent = enabled ? titleCase(status["recorder.state"] || "ready") : "Disabled";
    $("#recorder-capture-state").textContent = currentMode(status) === "stereo" ? "Stereo" : "Mono fallback";
    $("#recorder-format-state").textContent = ({ wav: "WAV / PCM", opus: "OGG / Opus", aac: "M4A / AAC" })[status["recording.format"]] || String(status["recording.format"] || "WAV").toUpperCase();
    const lastPath = status["last.output"] || "";
    $("#last-recording-name").textContent = lastPath ? basename(lastPath) : "No recordings yet";
    $("#last-recording-result").textContent = status["last.result"] || "Waiting for a completed call";
    $("#open-last-recording-button").disabled = !lastPath;
    const output = status["output.dir"] || DEFAULT_OUTPUT_DIR;
    $("#recorder-output-path").textContent = output;
    const exists = status["output.dir.exists"] === "1";
    const writable = status["output.dir.writable"] === "1";
    const free = formatBytes(status["output.dir.free_bytes"]);
    $("#recorder-output-health").textContent = !exists ? `Will be created · ${free} available` : !writable ? "Folder is not writable" : `${free} available`;
}

function populateRecorderForm() {
    const status = appState.status;
    $("#recording-enabled").checked = status["recording.enabled"] === "1";
    $("#recording-log-enabled").checked = status["recording.log_enabled"] !== "0";
    const radio = $(`input[name="recording-mode"][value="${currentMode(status)}"]`);
    if (radio) radio.checked = true;
    const format = status["recording.format"] || "wav";
    const available = new Set((status["recording.available_formats"] || "wav").split(",").map((item) => item.trim()));
    for (const option of $("#recording-format").options) option.disabled = !available.has(option.value);
    $("#recording-format").value = format;
    $("#output-dir").value = status["output.dir"] || DEFAULT_OUTPUT_DIR;
    $("#min-duration").value = status["recording.min_duration"] || "0";
    const supported = status["recording.voice_call_stereo_supported"] === "1";
    const note = status["recording.voice_call_probe_note"] || "Capability check is not available.";
    $("#recording-mode-note").textContent = `${supported ? "Stereo detected" : "Mono fallback detected"}. ${note}`;
}

function recordingLogFor(path) {
    return appState.recordingLog.find((entry) => entry.output === path || entry.path === path) || null;
}

function makeRecordingRow(recording, { compact = false, queueAction = false } = {}) {
    const row = element("article", { className: "data-row" });
    const selectLabel = element("label", { className: "row-select", title: `Select ${recording.name}` });
    const checkbox = element("input", { type: "checkbox", attributes: { "aria-label": `Select ${recording.name}` } });
    checkbox.value = recording.path;
    checkbox.checked = appState.selectedRecordings.has(recording.path);
    row.classList.toggle("is-selected", checkbox.checked);
    checkbox.addEventListener("change", () => {
        if (checkbox.checked) appState.selectedRecordings.add(recording.path);
        else appState.selectedRecordings.delete(recording.path);
        row.classList.toggle("is-selected", checkbox.checked);
        rememberSelection();
        updateSelectionUi();
    });
    selectLabel.appendChild(checkbox);

    const main = element("div", { className: "row-main" });
    main.appendChild(element("div", { className: "row-title", text: recording.name }));
    const log = recordingLogFor(recording.path);
    const meta = element("div", { className: "row-meta" });
    meta.append(
        element("span", { text: formatDate(recording.modifiedAt) }),
        element("span", { text: formatBytes(recording.sizeBytes) }),
    );
    if (Number(recording.audioChannels) >= 2) meta.appendChild(element("span", { text: "Stereo" }));
    else if (Number(recording.audioChannels) === 1) meta.appendChild(element("span", { text: "Mono" }));
    if (log?.direction) meta.appendChild(element("span", { text: titleCase(log.direction) }));
    if (Number.isFinite(Number(log?.duration))) meta.appendChild(element("span", { text: formatElapsed(0, Number(log.duration) * 1000) }));
    main.appendChild(meta);
    const statuses = element("div", { className: "row-statuses" });
    statuses.appendChild(recording.selectedTranscriptExists ? badge("Transcript ready", "success") : badge("No transcript", "neutral"));
    if (!compact) statuses.appendChild(badge(String(recording.audioFormat || "audio").toUpperCase(), "neutral"));
    main.appendChild(statuses);

    const actions = element("div", { className: "row-actions" });
    const openRecording = iconButton("play", "Open recording");
    openRecording.addEventListener("click", () => withPending(openRecording, () => run(`sh ./action.sh open-recording ${shellQuote(recording.path)}`), "Opening"));
    actions.appendChild(openRecording);
    if (queueAction) {
        const alreadyQueued = appState.activeView === "transcriber" && (appState.transcriber?.queue?.jobs || []).some((job) => job.recordingPath === recording.path && ["queued", "running"].includes(job.status));
        const queue = iconButton("captions", alreadyQueued ? "Already in transcription queue" : "Add to transcription queue");
        queue.disabled = alreadyQueued || appState.status["transcriber.enabled"] !== "1";
        queue.addEventListener("click", () => queueRecordings([recording.path], queue, { knownRecordings: [recording] }));
        actions.appendChild(queue);
    }
    if (recording.selectedTranscriptExists) {
        const preview = iconButton("eye", "Preview transcript");
        preview.addEventListener("click", () => openTranscriptPreview(recording.selectedTranscriptPath, preview));
        const openTranscript = iconButton("file-text", "Open transcript");
        openTranscript.addEventListener("click", () => withPending(openTranscript, () => run(`sh ./action.sh transcriber open-transcript ${shellQuote(recording.selectedTranscriptPath)}`), "Opening"));
        actions.append(preview, openTranscript);
    }
    row.append(selectLabel, main, actions);
    return row;
}

function renderLibrary() {
    const page = appState.recordings || { items: [], total: 0, offset: 0, limit: 40, hasMore: false };
    const list = $("#library-list");
    list.replaceChildren();
    if (!page.items?.length) showListState(list, "No recordings match these filters.");
    else for (const recording of page.items) list.appendChild(makeRecordingRow(recording, { queueAction: true }));
    const start = page.total ? page.offset + 1 : 0;
    const end = page.offset + (page.items?.length || 0);
    $("#library-result-count").textContent = `${start}-${end} of ${page.total} recordings`;
    const pageNumber = Math.floor(page.offset / page.limit) + 1;
    const pageCount = Math.max(1, Math.ceil(page.total / page.limit));
    $("#library-page-label").textContent = `Page ${pageNumber} of ${pageCount}`;
    $("#library-prev-button").disabled = page.offset <= 0;
    $("#library-next-button").disabled = !page.hasMore;
    updateSelectionUi();
}

function updateSelectionUi() {
    const count = appState.selectedRecordings.size;
    $("#library-selection-bar").hidden = count === 0;
    $("#library-selection-count").textContent = `${count} selected`;
    $("#transcriber-selection-count").textContent = `${count} selected`;
    $("#library-queue-button").disabled = count === 0;
    $("#queue-selected-button").disabled = count === 0;
    for (const checkbox of $$(".row-select input")) {
        checkbox.checked = appState.selectedRecordings.has(checkbox.value);
        checkbox.closest(".data-row")?.classList.toggle("is-selected", checkbox.checked);
    }
}

function populateTranscriberForm() {
    const status = appState.status;
    $("#transcriber-enabled").checked = status["transcriber.enabled"] === "1";
    $("#transcriber-output-dir").value = status["transcriber.output_dir"] || `${status["output.dir"] || DEFAULT_OUTPUT_DIR}/transcripts`;
    ensureSelectValue($("#transcriber-language"), status["transcriber.language"] || "en");
    $("#transcriber-speaker-self-name").value = status["transcriber.speaker_self_name"] || "Speaker A";
    $("#transcriber-speaker-remote-name").value = status["transcriber.speaker_remote_name"] || "Speaker B";
    ensureSelectValue($("#transcriber-output-format"), status["transcriber.output_format"] || "txt");
    const modelSelect = $("#transcriber-model-preset");
    const configuredModel = status["transcriber.model_url"] || DEFAULT_MODEL_URL;
    modelSelect.value = [...modelSelect.options].some((option) => option.value === configuredModel) ? configuredModel : DEFAULT_MODEL_URL;
    updateModelCompatibility();
}

function ensureSelectValue(select, value) {
    if (![...select.options].some((option) => option.value === value)) select.appendChild(element("option", { text: value, attributes: { value } }));
    select.value = value;
}

function updateModelCompatibility() {
    const language = $("#transcriber-language").value || "en";
    const url = $("#transcriber-model-preset").value;
    const englishOnly = /\.en\.bin(?:$|\?)/i.test(url);
    const note = $("#model-compatibility-note");
    if (englishOnly && !["en", "auto"].includes(language)) {
        note.textContent = "The selected English-only model does not match this source language.";
        note.style.color = "var(--warning)";
    } else {
        note.textContent = englishOnly ? "Optimized for English recordings." : "Supports multilingual recordings.";
        note.style.color = "";
    }
}

function renderTranscriber() {
    const transcriber = appState.transcriber || { dependencies: {}, queue: { jobs: [] }, runtime: {} };
    const jobs = transcriber.queue?.jobs || [];
    const mode = currentMode();
    const ready = mode === "stereo" ? transcriber.dependencies?.readyForStereo : transcriber.dependencies?.readyForMonoDiarization;
    $("#transcriber-engine-state").textContent = appState.status["transcriber.enabled"] !== "1" ? "Disabled" : ready ? "Ready" : "Components needed";
    const counts = jobs.reduce((all, job) => ({ ...all, [job.status]: (all[job.status] || 0) + 1 }), {});
    $("#transcriber-queue-state").textContent = jobs.length ? `${counts.running || 0} active · ${counts.queued || 0} queued` : "No jobs";
    const runtime = transcriber.runtime || {};
    const progress = Math.max(0, Math.min(100, Number(runtime.progress) || 0));
    $("#transcriber-stage").textContent = titleCase(runtime.stage || runtime.state || "idle");
    $("#transcriber-progress-label").textContent = `${progress}%`;
    $("#transcriber-progress").value = progress;
    $("#transcriber-progress").textContent = `${progress}%`;
    $("#transcriber-eta").textContent = formatEta(runtime.etaSeconds);
    const running = jobs.some((job) => job.status === "running");
    const queued = jobs.some((job) => job.status === "queued");
    $("#start-queue-button").disabled = !queued || running || appState.status["transcriber.enabled"] !== "1" || !ready;
    $("#pause-queue-button").disabled = !running;
    $("#resume-queue-button").disabled = runtime.state !== "paused" && (!queued || running);
    $("#stop-queue-button").disabled = !running && !["stopping", "resuming"].includes(runtime.state);
    renderQueue(jobs);
    renderTranscriberRecordings();
    renderComponents();
}

function renderQueue(jobs) {
    const list = $("#queue-list");
    list.replaceChildren();
    if (!jobs.length) {
        showListState(list, "No transcription jobs. Select recordings below to build a queue.");
        return;
    }
    const visible = jobs.slice(0, 120);
    visible.forEach((job, index) => list.appendChild(makeQueueRow(job, index, jobs)));
    if (jobs.length > visible.length) list.appendChild(element("div", { className: "empty-state", text: `Showing the first ${visible.length} of ${jobs.length} jobs.` }));
}

function makeQueueRow(job, index, jobs) {
    const row = element("article", { className: "data-row" });
    const main = element("div", { className: "row-main" });
    main.appendChild(element("div", { className: "row-title", text: basename(job.recordingPath) }));
    const statusLine = element("div", { className: "row-statuses" }, [badge(titleCase(job.status), statusTone(job.status)), badge(titleCase(job.diarizationMode || "pending"), "neutral")]);
    main.appendChild(statusLine);
    const meta = element("div", { className: "row-meta" });
    meta.append(
        element("span", { text: `Queued ${formatDate(job.createdAt)}` }),
        element("span", { text: `Started ${formatDate(job.startedAt)}` }),
    );
    if (job.completedAt) meta.appendChild(element("span", { text: `Completed ${formatDate(job.completedAt)}` }));
    if (job.startedAt) meta.appendChild(element("span", { text: `Elapsed ${formatElapsed(job.startedAt, job.completedAt || Date.now())}` }));
    if (job.id === appState.transcriber?.runtime?.activeJobId && Number(appState.transcriber.runtime.etaSeconds) > 0) {
        meta.appendChild(element("span", { text: formatEta(appState.transcriber.runtime.etaSeconds) }));
    }
    main.appendChild(meta);
    main.appendChild(element("small", { className: "row-path", text: `Transcript: ${job.transcriptPath}` }));
    if (job.status === "running" || Number(job.progress) > 0) {
        const progressWrap = element("div", { className: "queue-progress" });
        progressWrap.append(element("span", { className: "row-meta", text: `${titleCase(job.stage || job.status)} · ${Number(job.progress) || 0}%` }));
        const progress = element("progress", { attributes: { max: "100", value: String(Number(job.progress) || 0) } });
        progressWrap.appendChild(progress);
        main.appendChild(progressWrap);
    }
    if (job.error) main.appendChild(element("div", { className: "row-error", text: job.error }));
    const actions = element("div", { className: "row-actions" });
    if (job.status === "queued") {
        const up = iconButton("arrow-up", "Move job up");
        up.disabled = index === 0 || jobs[index - 1]?.status !== "queued";
        up.addEventListener("click", () => queueControl("move-up", job.id, up));
        const down = iconButton("arrow-down", "Move job down");
        down.disabled = index === jobs.length - 1 || jobs[index + 1]?.status !== "queued";
        down.addEventListener("click", () => queueControl("move-down", job.id, down));
        actions.append(up, down);
    }
    if (["failed", "cancelled", "skipped"].includes(job.status)) {
        const retry = iconButton("rotate-cw", "Retry job");
        retry.addEventListener("click", () => queueControl("retry", job.id, retry));
        actions.appendChild(retry);
    }
    if (job.status === "succeeded") {
        const preview = iconButton("eye", "Preview transcript");
        preview.addEventListener("click", () => openTranscriptPreview(job.transcriptPath, preview));
        const open = iconButton("file-text", "Open transcript");
        open.addEventListener("click", () => withPending(open, () => run(`sh ./action.sh transcriber open-transcript ${shellQuote(job.transcriptPath)}`), "Opening"));
        actions.append(preview, open);
    }
    if (job.status !== "running") {
        const remove = iconButton("trash-2", "Remove job", "icon-button danger");
        remove.addEventListener("click", () => queueControl("remove", job.id, remove));
        actions.appendChild(remove);
    }
    row.append(element("span", { className: "row-select" }, badge(String(index + 1), "neutral")), main, actions);
    return row;
}

function renderTranscriberRecordings() {
    const page = appState.recordings || { items: [], total: 0, offset: 0, limit: 20, hasMore: false };
    const list = $("#transcriber-recording-list");
    list.replaceChildren();
    if (!page.items?.length) showListState(list, "No recordings match this search.");
    else for (const recording of page.items) list.appendChild(makeRecordingRow(recording, { compact: true, queueAction: true }));
    const controls = element("div", { className: "pagination" });
    const previous = element("button", { className: "text-button secondary", text: "Previous", type: "button" });
    previous.disabled = page.offset <= 0;
    previous.addEventListener("click", () => changeTranscriberPage(-1));
    const next = element("button", { className: "text-button secondary", text: "Next", type: "button" });
    next.disabled = !page.hasMore;
    next.addEventListener("click", () => changeTranscriberPage(1));
    controls.append(previous, element("span", { text: `${page.offset + 1}-${page.offset + (page.items?.length || 0)} of ${page.total}` }), next);
    list.appendChild(controls);
    updateSelectionUi();
}

function componentStatus(key) {
    return appState.components[`component.${key}.status`] || "missing";
}

function renderComponents() {
    const list = $("#component-list");
    list.replaceChildren();
    const mono = currentMode() === "mono";
    $("#component-compatibility-note").textContent = mono
        ? "Mono fallback uses the whisper.cpp CLI and TinyDiarize model."
        : "Stereo uses the whisper.cpp CLI and Whisper model. TinyDiarize is optional unless mono fallback is selected.";
    const labels = { whisper_cli: "whisper.cpp CLI", base_model: "Whisper model", tinydiarize_model: "TinyDiarize model" };
    for (const key of COMPONENT_KEYS) {
        const status = componentStatus(key);
        const prefix = `component.${key}`;
        const row = element("article", { className: "component-row" });
        const identity = element("div");
        identity.append(element("span", { text: labels[key] }), element("strong", { text: titleCase(status) }));
        const required = key === "whisper_cli" || (mono ? key === "tinydiarize_model" : key === "base_model");
        identity.appendChild(element("small", { text: required ? "Required for current mode" : "Optional for current mode" }));
        const detail = element("div", { className: "component-progress" });
        const downloaded = Number(appState.components[`${prefix}.bytes_downloaded`] || 0);
        const total = Number(appState.components[`${prefix}.bytes_total`] || 0);
        const speed = Number(appState.components[`${prefix}.bytes_per_second`] || 0);
        const progress = status === "ready" ? 100 : Math.max(0, Number(appState.components[`${prefix}.progress`]) || 0);
        if (["downloading", "installing", "failed"].includes(status)) {
            detail.appendChild(element("progress", { attributes: { max: "100", value: String(progress) } }));
            const speedText = speed > 0 ? ` · ${formatBytes(speed)}/s` : "";
            detail.appendChild(element("small", { text: `${formatBytes(downloaded)} / ${formatBytes(total)} · ${progress}%${speedText}` }));
            const source = appState.components[`${prefix}.url`] || appState.components[`${prefix}.source_detail`] || "";
            if (source) detail.appendChild(element("small", { className: "component-path", text: source }));
        } else {
            const path = appState.components[`${prefix}.path`] || "Not installed";
            detail.appendChild(element("small", { className: "component-path", text: status === "ready" ? basename(path) : "Not installed" }));
        }
        const error = appState.components[`${prefix}.error`];
        if (error) detail.appendChild(element("small", { className: "row-error", text: error }));
        const actions = element("div", { className: "component-actions" });
        if (status === "failed") {
            const repair = iconButton("wrench", `Repair ${labels[key]}`);
            repair.addEventListener("click", () => prepareComponents(repair));
            actions.appendChild(repair);
        }
        if (status === "ready") {
            const remove = iconButton("trash-2", `Remove ${labels[key]}`, "icon-button danger");
            remove.addEventListener("click", () => removeComponent(key, labels[key], remove));
            actions.appendChild(remove);
        }
        row.append(identity, detail, actions);
        list.appendChild(row);
    }
}

function renderDiagnostics() {
    const status = appState.status;
    $("#debug-enabled").checked = status["debug.enabled"] === "1";
    $("#raw-status-output").textContent = formatRaw(status);
    $("#raw-transcriber-output").textContent = appState.transcriber ? JSON.stringify(appState.transcriber, null, 2) : "Transcriber status unavailable.";
    $("#raw-components-output").textContent = formatRaw(appState.components);
}

function showDiagnosticResult(text) {
    const details = $("#diagnostic-result-details");
    $("#diagnostic-output").textContent = text;
    details.hidden = false;
    details.open = true;
}

function updateSaveBar() {
    $("#save-bar").hidden = appState.dirtySections.size === 0;
}

function markSettingsDirty(section) {
    markDirty(section);
    updateSaveBar();
    if (section === "transcriber") updateModelCompatibility();
}

async function saveChanges(button) {
    const commands = [];
    if (appState.dirtySections.has("recorder")) {
        const enabled = $("#recording-enabled").checked ? "1" : "0";
        const log = $("#recording-log-enabled").checked ? "1" : "0";
        const mode = $("input[name='recording-mode']:checked")?.value === "mono" ? "0" : "1";
        const format = $("#recording-format").value || "wav";
        const output = $("#output-dir").value.trim() || DEFAULT_OUTPUT_DIR;
        const duration = Math.max(0, Number.parseInt($("#min-duration").value || "0", 10) || 0);
        commands.push(
            `sh ./action.sh config set recording.enabled ${enabled}`,
            `sh ./action.sh config set recording.log_enabled ${log}`,
            `sh ./action.sh config set recording.stereo ${mode}`,
            `sh ./action.sh config set recording.format ${shellQuote(format)}`,
            `sh ./action.sh config set output.dir ${shellQuote(output)}`,
            `sh ./action.sh config set recording.min_duration ${duration}`,
        );
    }
    if (appState.dirtySections.has("transcriber")) {
        const wasEnabled = appState.status["transcriber.enabled"] === "1";
        const enabled = $("#transcriber-enabled").checked;
        let removeComponents = false;
        if (!wasEnabled && enabled) {
            const choice = await requestChoice({ title: "Enable transcriber", message: "Enable local transcription? Components must be prepared before jobs can start.", actions: [{ label: "Enable", value: "enable" }, { label: "Cancel", value: "cancel", className: "text-button secondary" }] });
            if (choice.value !== "enable") return;
        }
        if (wasEnabled && !enabled) {
            const choice = await requestChoice({ title: "Disable transcriber", message: "Queued jobs will remain saved. Existing transcripts are kept.", checkboxLabel: "Also remove downloaded transcriber components", actions: [{ label: "Disable", value: "disable", className: "text-button danger" }, { label: "Cancel", value: "cancel", className: "text-button secondary" }] });
            if (choice.value !== "disable") return;
            removeComponents = choice.checked;
            commands.push("sh ./action.sh transcriber stop");
        }
        const transcriptOutput = $("#transcriber-output-dir").value.trim() || `${$("#output-dir").value.trim() || DEFAULT_OUTPUT_DIR}/transcripts`;
        const selfName = $("#transcriber-speaker-self-name").value.trim() || "Speaker A";
        const remoteName = $("#transcriber-speaker-remote-name").value.trim() || "Speaker B";
        const modelUrl = $("#transcriber-model-preset").value || DEFAULT_MODEL_URL;
        commands.push(
            `sh ./action.sh config set transcriber.enabled ${enabled ? "1" : "0"}`,
            `sh ./action.sh config set transcriber.output_dir ${shellQuote(transcriptOutput)}`,
            `sh ./action.sh config set transcriber.language ${shellQuote($("#transcriber-language").value || "en")}`,
            `sh ./action.sh config set transcriber.speaker_self_name ${shellQuote(selfName)}`,
            `sh ./action.sh config set transcriber.speaker_remote_name ${shellQuote(remoteName)}`,
            `sh ./action.sh config set transcriber.output_format ${shellQuote($("#transcriber-output-format").value || "txt")}`,
            `sh ./action.sh config set transcriber.whisper_manifest_url ${shellQuote(DEFAULT_MANIFEST_URL)}`,
            `sh ./action.sh config set transcriber.model_url ${shellQuote(modelUrl)}`,
            `sh ./action.sh config set transcriber.tinydiarize_model_url ${shellQuote(DEFAULT_TINYDIARIZE_URL)}`,
            "sh ./action.sh transcriber components-reset",
        );
        if (removeComponents) commands.push("sh ./action.sh transcriber remove-deps");
    }
    if (appState.dirtySections.has("recorder")) commands.push("sh ./action.sh restart");
    if (!commands.length) return;
    await withPending(button, async () => {
        await run(commands.join(" && "));
        clearDirty();
        updateSaveBar();
        showToast("Settings saved");
        await refreshActiveView({ forceForm: true });
    }, "Saving");
}

async function resetRecorder(button) {
    const choice = await requestChoice({ title: "Reset recorder settings", message: "Restore recorder defaults? Existing recordings and transcripts will not be changed.", actions: [{ label: "Reset", value: "reset", className: "text-button danger" }, { label: "Cancel", value: "cancel", className: "text-button secondary" }] });
    if (choice.value !== "reset") return;
    await withPending(button, async () => {
        await run([
            "sh ./action.sh config set recording.enabled 0", "sh ./action.sh config set recording.log_enabled 1", "sh ./action.sh config set recording.stereo 1",
            "sh ./action.sh config set recording.format wav", `sh ./action.sh config set output.dir ${shellQuote(DEFAULT_OUTPUT_DIR)}`, "sh ./action.sh config set recording.min_duration 0", "sh ./action.sh restart",
        ].join(" && "));
        appState.dirtySections.delete("recorder");
        updateSaveBar();
        await refreshActiveView({ forceForm: true });
        showToast("Recorder defaults restored");
    }, "Resetting");
}

async function queueRecordings(paths, button, { knownRecordings = [], clearSelection = false } = {}) {
    if (!paths.length) return showToast("Select at least one recording");
    if (appState.status["transcriber.enabled"] !== "1") {
        showActionError(new Error("Enable the transcriber before adding jobs."));
        return;
    }
    const visible = new Map([...(appState.recordings.items || []), ...knownRecordings].map((item) => [item.path, item]));
    let policy = "skip";
    if (paths.some((path) => visible.get(path)?.selectedTranscriptExists)) {
        const choice = await requestChoice({ title: "Existing transcripts", message: "Some selected recordings already have a transcript in the selected format.", actions: [{ label: "Skip existing", value: "skip" }, { label: "Overwrite", value: "overwrite", className: "text-button secondary" }, { label: "Cancel", value: "cancel", className: "text-button secondary" }] });
        if (choice.value === "cancel") return;
        policy = choice.value;
    }
    await withPending(button, async () => {
        await run(`sh ./action.sh transcriber enqueue ${policy} ${paths.map(shellQuote).join(" ")}`);
        if (clearSelection) {
            for (const path of paths) appState.selectedRecordings.delete(path);
            rememberSelection();
        }
        showToast(paths.length === 1 ? "Recording added to the queue" : "Recordings added to the queue");
        await refreshActiveView();
    }, "Queueing");
}

async function queueSelected(button) {
    return queueRecordings([...appState.selectedRecordings], button, { clearSelection: true });
}

async function queueControl(command, id, button) {
    await withPending(button, async () => {
        await run(`sh ./action.sh transcriber ${command}${id ? ` ${shellQuote(id)}` : ""}`);
        await refreshActiveView({ silent: true });
    }, "Updating");
}

async function openTranscriptPreview(path, button) {
    appState.currentPreviewPath = path;
    const dialog = $("#preview-dialog");
    $("#preview-dialog-title").textContent = basename(path);
    $("#preview-content").textContent = "Loading preview...";
    if (!dialog.open) dialog.showModal();
    await withPending(button, async () => {
        const raw = await run(`sh ./action.sh transcriber preview ${shellQuote(path)} 20000`);
        let preview;
        try { preview = JSON.parse(raw); } catch (_) { preview = { available: false, error: raw || "Preview failed" }; }
        $("#preview-content").textContent = preview.available ? `${preview.text}${preview.truncated ? "\n\n[Preview truncated]" : ""}` : preview.error || "Preview unavailable.";
    }, "Loading");
}

async function prepareComponents(button) {
    await withPending(button, async () => {
        const modelUrl = $("#transcriber-model-preset").value || DEFAULT_MODEL_URL;
        await run([
            `sh ./action.sh config set transcriber.whisper_manifest_url ${shellQuote(DEFAULT_MANIFEST_URL)}`,
            `sh ./action.sh config set transcriber.model_url ${shellQuote(modelUrl)}`,
            `sh ./action.sh config set transcriber.tinydiarize_model_url ${shellQuote(DEFAULT_TINYDIARIZE_URL)}`,
            "sh ./action.sh transcriber components-reset",
        ].join(" && "));
        const metadata = await runCapture("sh ./action.sh transcriber components-refresh-metadata");
        if (!metadata.ok) showActionError(new Error(`Could not refresh download sizes: ${metadata.stderr}`));
        await refreshActiveView({ silent: true });
        const whisper = Number(appState.components["component.whisper_cli.bytes_total"] || 0);
        const stereoBytes = whisper + Number(appState.components["component.base_model.bytes_total"] || 0);
        const monoBytes = whisper + Number(appState.components["component.tinydiarize_model.bytes_total"] || 0);
        const choice = await requestChoice({
            title: "Prepare components",
            message: `Stereo: whisper.cpp CLI and Whisper model (${formatBytes(stereoBytes)}).\nMono fallback: whisper.cpp CLI and TinyDiarize model (${formatBytes(monoBytes)}).`,
            actions: [{ label: "Stereo", value: "stereo" }, { label: "Mono fallback", value: "mono", className: "text-button secondary" }, { label: "Cancel", value: "cancel", className: "text-button secondary" }],
        });
        if (choice.value === "cancel") return;
        await run(`sh ./action.sh transcriber install-deps ${choice.value}`);
        showToast("Component preparation started");
        await refreshActiveView({ silent: true });
    }, "Preparing");
}

async function removeComponent(key, label, button) {
    const choice = await requestChoice({ title: `Remove ${label}`, message: "Remove this downloaded component? Recordings, transcripts, and queue state are kept.", actions: [{ label: "Remove", value: "remove", className: "text-button danger" }, { label: "Cancel", value: "cancel", className: "text-button secondary" }] });
    if (choice.value !== "remove") return;
    await withPending(button, async () => {
        await run(`sh ./action.sh transcriber remove-component ${key}`);
        await refreshActiveView({ silent: true });
        showToast(`${label} removed`);
    }, "Removing");
}

async function selectAllResults(button, scope) {
    const filters = appState.filters[scope];
    await withPending(button, async () => {
        let offset = 0;
        let total = Infinity;
        let pages = 0;
        while (offset < total && pages < 40) {
            const snapshot = await getSnapshot(scope === "library" ? "library" : "transcriber", { ...filters, offset, limit: 250 });
            const page = snapshot.recordings || { total: 0, items: [] };
            total = page.total;
            for (const item of page.items || []) appState.selectedRecordings.add(item.path);
            if (!page.hasMore || !page.items?.length) break;
            offset += page.items.length;
            pages += 1;
        }
        rememberSelection();
        updateSelectionUi();
        showToast(`${appState.selectedRecordings.size} recordings selected`);
    }, "Selecting");
}

function changeLibraryPage(delta) {
    const filter = appState.filters.library;
    filter.offset = Math.max(0, filter.offset + delta * filter.limit);
    refreshActiveView();
}

function changeTranscriberPage(delta) {
    const filter = appState.filters.transcriber;
    filter.offset = Math.max(0, filter.offset + delta * filter.limit);
    refreshActiveView();
}

function updateFilter(scope) {
    const filter = appState.filters[scope];
    if (scope === "library") {
        filter.search = $("#library-search").value.trim();
        filter.transcript = $("#library-transcript-filter").value;
        filter.channel = "all";
        filter.sort = $("#library-sort").value;
    } else {
        filter.search = $("#transcriber-search").value.trim();
    }
    filter.offset = 0;
    refreshActiveView();
}

function shouldPoll() {
    if (appState.activeView !== "transcriber" || document.hidden) return false;
    const runtime = appState.transcriber?.runtime?.state || "";
    const componentsRunning = appState.components["transcriber.components.running"] === "1";
    return componentsRunning || ["running", "resuming", "stopping"].includes(runtime);
}

function updatePolling() {
    if (!shouldPoll()) return stopPolling();
    if (appState.pollTimer) return;
    appState.pollTimer = window.setTimeout(pollOnce, 2500);
}

async function pollOnce() {
    appState.pollTimer = null;
    if (!shouldPoll() || appState.pollInFlight) return;
    appState.pollInFlight = true;
    try { await refreshActiveView({ silent: true }); } finally {
        appState.pollInFlight = false;
        updatePolling();
    }
}

function stopPolling() {
    if (appState.pollTimer) window.clearTimeout(appState.pollTimer);
    appState.pollTimer = null;
}

function bindEvents() {
    for (const button of $$(`[data-view-target]`)) button.addEventListener("click", () => setView(button.dataset.viewTarget));
    const tabs = $$(".primary-nav [role='tab']");
    for (const [index, tab] of tabs.entries()) {
        tab.addEventListener("keydown", (event) => {
            let nextIndex = null;
            if (["ArrowDown", "ArrowRight"].includes(event.key)) nextIndex = (index + 1) % tabs.length;
            if (["ArrowUp", "ArrowLeft"].includes(event.key)) nextIndex = (index - 1 + tabs.length) % tabs.length;
            if (event.key === "Home") nextIndex = 0;
            if (event.key === "End") nextIndex = tabs.length - 1;
            if (nextIndex === null) return;
            event.preventDefault();
            tabs[nextIndex].focus();
            setView(tabs[nextIndex].dataset.viewTarget);
        });
    }
    $("#refresh-view-button").addEventListener("click", () => refreshActiveView());
    $("#dismiss-action-error").addEventListener("click", dismissActionError);
    $("#open-output-button").addEventListener("click", (event) => withPending(event.currentTarget, () => run("sh ./action.sh open-output-dir"), "Opening"));
    $("#open-library-folder-button").addEventListener("click", (event) => withPending(event.currentTarget, () => run("sh ./action.sh open-output-dir"), "Opening"));
    $("#open-last-recording-button").addEventListener("click", (event) => withPending(event.currentTarget, () => run(`sh ./action.sh open-recording ${shellQuote(appState.status["last.output"])}`), "Opening"));
    $("#open-transcript-folder-button").addEventListener("click", (event) => withPending(event.currentTarget, () => run("sh ./action.sh open-transcript-output-dir"), "Opening"));
    $("#reset-recorder-button").addEventListener("click", (event) => resetRecorder(event.currentTarget));
    $("#save-changes-button").addEventListener("click", (event) => saveChanges(event.currentTarget).catch(showActionError));
    $("#discard-changes-button").addEventListener("click", async () => { clearDirty(); updateSaveBar(); await refreshActiveView({ forceForm: true }); });

    const recorderFields = ["#recording-enabled", "#recording-log-enabled", "#recording-format", "#output-dir", "#min-duration"];
    for (const selector of recorderFields) $(selector).addEventListener("input", () => markSettingsDirty("recorder"));
    for (const radio of $$("input[name='recording-mode']")) radio.addEventListener("change", () => markSettingsDirty("recorder"));
    const transcriberFields = ["#transcriber-enabled", "#transcriber-output-dir", "#transcriber-language", "#transcriber-speaker-self-name", "#transcriber-speaker-remote-name", "#transcriber-output-format", "#transcriber-model-preset"];
    for (const selector of transcriberFields) $(selector).addEventListener("input", () => markSettingsDirty("transcriber"));

    $("#library-search").addEventListener("input", () => { window.clearTimeout(searchTimer); searchTimer = window.setTimeout(() => updateFilter("library"), 350); });
    for (const selector of ["#library-transcript-filter", "#library-sort"]) $(selector).addEventListener("change", () => updateFilter("library"));
    $("#transcriber-search").addEventListener("input", () => { window.clearTimeout(searchTimer); searchTimer = window.setTimeout(() => updateFilter("transcriber"), 350); });
    $("#library-prev-button").addEventListener("click", () => changeLibraryPage(-1));
    $("#library-next-button").addEventListener("click", () => changeLibraryPage(1));
    $("#library-select-page-button").addEventListener("click", () => { for (const item of appState.recordings.items || []) appState.selectedRecordings.add(item.path); rememberSelection(); updateSelectionUi(); });
    $("#transcriber-select-page-button").addEventListener("click", () => { for (const item of appState.recordings.items || []) appState.selectedRecordings.add(item.path); rememberSelection(); updateSelectionUi(); });
    $("#library-clear-selection-button").addEventListener("click", () => { appState.selectedRecordings.clear(); rememberSelection(); updateSelectionUi(); });
    $("#library-queue-button").addEventListener("click", (event) => queueSelected(event.currentTarget));
    $("#queue-selected-button").addEventListener("click", (event) => queueSelected(event.currentTarget));

    $("#start-queue-button").addEventListener("click", (event) => queueControl("start-worker", "", event.currentTarget));
    $("#pause-queue-button").addEventListener("click", (event) => queueControl("pause", "", event.currentTarget));
    $("#resume-queue-button").addEventListener("click", (event) => queueControl("resume", "", event.currentTarget));
    $("#stop-queue-button").addEventListener("click", (event) => queueControl("stop", "", event.currentTarget));
    $("#clear-completed-button").addEventListener("click", (event) => queueControl("clear", "", event.currentTarget));
    $("#prepare-components-button").addEventListener("click", (event) => prepareComponents(event.currentTarget));

    $("#save-debug-button").addEventListener("click", (event) => withPending(event.currentTarget, async () => {
        await run(`sh ./action.sh config set debug.enabled ${$("#debug-enabled").checked ? "1" : "0"}`);
        showToast("Debug setting saved");
        await refreshActiveView();
    }, "Saving"));
    $("#run-probe-button").addEventListener("click", (event) => withPending(event.currentTarget, async () => { showDiagnosticResult(await run("sh ./action.sh probe") || "Probe returned no output."); }, "Probing"));
    $("#restart-daemon-button").addEventListener("click", (event) => withPending(event.currentTarget, async () => { await run("sh ./action.sh restart"); await refreshActiveView(); showToast("Recorder daemon restarted"); }, "Restarting"));
    $("#load-logs-button").addEventListener("click", (event) => withPending(event.currentTarget, async () => { showDiagnosticResult(await run("sh ./action.sh logs") || "No logs available."); }, "Loading"));

    $("#close-preview-button").addEventListener("click", () => $("#preview-dialog").close());
    $("#close-preview-footer-button").addEventListener("click", () => $("#preview-dialog").close());
    $("#open-preview-file-button").addEventListener("click", (event) => withPending(event.currentTarget, () => run(`sh ./action.sh transcriber open-transcript ${shellQuote(appState.currentPreviewPath)}`), "Opening"));
    document.addEventListener("visibilitychange", () => document.hidden ? stopPolling() : updatePolling());
}

function addSelectAllActions() {
    const librarySelectAll = element("button", { className: "text-button secondary", text: "Select all results", type: "button" });
    librarySelectAll.addEventListener("click", () => selectAllResults(librarySelectAll, "library"));
    $("#library-selection-bar > div").prepend(librarySelectAll);
    const transcriberSelectAll = element("button", { className: "text-button secondary", text: "Select all results", type: "button" });
    transcriberSelectAll.addEventListener("click", () => selectAllResults(transcriberSelectAll, "transcriber"));
    $("#transcriber-select-page-button").insertAdjacentElement("afterend", transcriberSelectAll);
}

function initialize() {
    const languageSelect = $("#transcriber-language");
    for (const [value, label] of LANGUAGES) languageSelect.appendChild(element("option", { text: label, attributes: { value } }));
    renderIcons();
    bindEvents();
    addSelectAllActions();
    updateSelectionUi();
    setView(appState.activeView, { refresh: false });
    refreshActiveView({ forceForm: true });
}

initialize();

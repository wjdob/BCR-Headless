const now = Date.now();
const outputDir = "/sdcard/Recordings/BCRHeadless";
const transcriptDir = `${outputDir}/transcripts`;
export const MOCK_SCENARIO = typeof window === "undefined"
    ? "default"
    : new URLSearchParams(window.location.search).get("scenario") || "default";

const recordings = Array.from({ length: 96 }, (_, index) => {
    const number = String(15870000000 + index);
    const date = new Date(now - index * 3_700_000);
    const name = `${date.toISOString().replace(/[-:T]/g, "").slice(0, 15)}_${index % 2 ? "out" : "in"}_${number}_extended-call-recording.wav`;
    const transcriptExists = index % 3 === 0;
    return {
        path: `${outputDir}/${name}`,
        name,
        sizeBytes: 1_800_000 + index * 27_500,
        modifiedAt: date.toISOString(),
        audioChannels: index % 5 === 0 ? 1 : 2,
        transcriptTxtPath: `${transcriptDir}/${name.replace(/\.wav$/i, ".txt")}`,
        transcriptDocxPath: `${transcriptDir}/${name.replace(/\.wav$/i, ".docx")}`,
        transcriptTxtExists: transcriptExists,
        transcriptDocxExists: false,
        selectedTranscriptPath: `${transcriptDir}/${name.replace(/\.wav$/i, ".txt")}`,
        selectedTranscriptExists: transcriptExists,
        audioFormat: "wav",
    };
});

const status = {
    "module.id": "bcr.headless",
    "module.version": "1.3.0",
    "recording.enabled": "1",
    "recording.log_enabled": "1",
    "recording.stereo": "1",
    "recording.format": "wav",
    "recording.available_formats": "wav,opus,aac",
    "recording.detected_mode": "stereo",
    "recording.voice_call_stereo_supported": "1",
    "recording.voice_call_probe_status": "supported",
    "recording.voice_call_probe_note": "Stereo VOICE_CALL capture initialized successfully",
    "recording.min_duration": "2",
    "output.dir": outputDir,
    "output.dir.exists": "1",
    "output.dir.writable": "1",
    "output.dir.free_bytes": String(18 * 1024 * 1024 * 1024),
    "daemon.running": "1",
    "recorder.state": "ready",
    "last.result": "Saved stereo recording",
    "last.output": recordings[0].path,
    "debug.enabled": "0",
    "transcriber.enabled": "1",
    "transcriber.output_dir": transcriptDir,
    "transcriber.output_dir.exists": "1",
    "transcriber.output_dir.writable": "1",
    "transcriber.output_dir.free_bytes": String(18 * 1024 * 1024 * 1024),
    "transcriber.language": "en",
    "transcriber.output_format": "txt",
    "transcriber.speaker_self_name": "Speaker A",
    "transcriber.speaker_remote_name": "Speaker B",
    "transcriber.whisper_manifest_url": "https://github.com/wjdob/BCR-Headless/releases/download/transcriber-tools/transcriber-tools.env",
    "transcriber.model_url": "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
    "transcriber.tinydiarize_model_url": "https://huggingface.co/akashmjn/tinydiarize-whisper.cpp/resolve/main/ggml-small.en-tdrz.bin",
};

const components = {
    "transcriber.components.state": "idle",
    "transcriber.components.running": "0",
    "transcriber.components.error": "",
    "component.whisper_cli.label": "whisper.cpp CLI",
    "component.whisper_cli.status": "ready",
    "component.whisper_cli.progress": "100",
    "component.whisper_cli.bytes_downloaded": "2051992",
    "component.whisper_cli.bytes_total": "2051992",
    "component.whisper_cli.bytes_per_second": "0",
    "component.whisper_cli.path": "/data/adb/modules/bcr.headless/tools/transcriber/whisper-cli",
    "component.whisper_cli.source_kind": "manifest",
    "component.whisper_cli.source_detail": "Repository ABI manifest",
    "component.whisper_cli.error": "",
    "component.base_model.label": "Whisper Base English",
    "component.base_model.status": "ready",
    "component.base_model.progress": "100",
    "component.base_model.bytes_downloaded": "147964211",
    "component.base_model.bytes_total": "147964211",
    "component.base_model.bytes_per_second": "0",
    "component.base_model.path": "/data/adb/modules/bcr.headless/tools/transcriber/models/ggml-base.en.bin",
    "component.base_model.url": status["transcriber.model_url"],
    "component.base_model.error": "",
    "component.tinydiarize_model.label": "TinyDiarize Small English",
    "component.tinydiarize_model.status": "ready",
    "component.tinydiarize_model.progress": "100",
    "component.tinydiarize_model.bytes_downloaded": "487614184",
    "component.tinydiarize_model.bytes_total": "487614184",
    "component.tinydiarize_model.bytes_per_second": "0",
    "component.tinydiarize_model.path": "/data/adb/modules/bcr.headless/tools/transcriber/models/ggml-small.en-tdrz.bin",
    "component.tinydiarize_model.url": status["transcriber.tinydiarize_model_url"],
    "component.tinydiarize_model.error": "",
};

let queue = [
    {
        id: "mock-running",
        recordingPath: recordings[1].path,
        transcriptPath: recordings[1].selectedTranscriptPath,
        language: "en",
        speakerSelfName: "Speaker A",
        speakerRemoteName: "Speaker B",
        format: "txt",
        overwrite: false,
        status: "running",
        progress: 42,
        stage: "transcribing",
        createdAt: new Date(now - 240000).toISOString(),
        startedAt: new Date(now - 180000).toISOString(),
        completedAt: null,
        error: null,
        audioChannels: 2,
        diarizationMode: "stereo",
    },
    {
        id: "mock-queued",
        recordingPath: recordings[2].path,
        transcriptPath: recordings[2].selectedTranscriptPath,
        language: "en",
        speakerSelfName: "Speaker A",
        speakerRemoteName: "Speaker B",
        format: "txt",
        overwrite: false,
        status: "queued",
        progress: 0,
        stage: "queued",
        createdAt: new Date(now - 120000).toISOString(),
        startedAt: null,
        completedAt: null,
        error: null,
        audioChannels: 2,
        diarizationMode: null,
    },
];

let runtime = {
    state: "running",
    stage: "transcribing",
    activeJobId: "mock-running",
    activeFile: recordings[1].path,
    progress: 42,
    etaSeconds: 74,
    diarizationMode: "stereo",
    error: null,
};
let componentTicks = -1;

function applyMockScenario() {
    if (MOCK_SCENARIO === "empty") {
        queue = [];
        runtime = { state: "idle", stage: null, activeJobId: null, activeFile: null, progress: null, etaSeconds: null, diarizationMode: null, error: null };
        status["last.output"] = "";
        status["last.result"] = "";
    }
    if (MOCK_SCENARIO === "download") {
        queue = queue.map((job) => job.status === "running" ? { ...job, status: "queued", stage: "queued", progress: 0, startedAt: null } : job);
        runtime = { state: "idle", stage: null, activeJobId: null, activeFile: null, progress: null, etaSeconds: null, diarizationMode: null, error: null };
        componentTicks = 1;
        for (const key of ["whisper_cli", "base_model"]) {
            components[`component.${key}.status`] = "downloading";
            components[`component.${key}.progress`] = "22";
        }
        components["transcriber.components.running"] = "1";
        components["transcriber.components.state"] = "downloading";
    }
    if (MOCK_SCENARIO === "failure") {
        queue = [{
            ...queue[0], status: "failed", stage: "failed", progress: 0,
            completedAt: new Date(now - 30000).toISOString(), error: "Whisper process exited before producing timestamped output",
        }];
        runtime = {
            state: "failed", stage: "failed", activeJobId: queue[0].id, activeFile: queue[0].recordingPath,
            progress: 0, etaSeconds: null, diarizationMode: "stereo", error: queue[0].error,
        };
        components["component.base_model.status"] = "failed";
        components["component.base_model.error"] = "Checksum did not match the downloaded model";
        components["transcriber.components.state"] = "failed";
        components["transcriber.components.error"] = "Whisper model installation failed";
    }
    if (MOCK_SCENARIO === "debug") {
        status["debug.enabled"] = "1";
        status["recorder.state"] = "monitoring";
    }
}

applyMockScenario();

function recordingPage(params) {
    const search = (params.search || "").toLowerCase();
    let items = (MOCK_SCENARIO === "empty" ? [] : recordings)
        .filter((item) => !search || item.name.toLowerCase().includes(search));
    if (params.transcript === "ready") items = items.filter((item) => item.selectedTranscriptExists);
    if (params.transcript === "missing") items = items.filter((item) => !item.selectedTranscriptExists);
    if (params.channel === "stereo") items = items.filter((item) => item.audioChannels >= 2);
    if (params.channel === "mono") items = items.filter((item) => item.audioChannels === 1);
    if (params.sort === "oldest") items.sort((a, b) => a.modifiedAt.localeCompare(b.modifiedAt));
    else if (params.sort === "name") items.sort((a, b) => a.name.localeCompare(b.name));
    else if (params.sort === "largest") items.sort((a, b) => b.sizeBytes - a.sizeBytes);
    else if (params.sort === "smallest") items.sort((a, b) => a.sizeBytes - b.sizeBytes);
    else items.sort((a, b) => b.modifiedAt.localeCompare(a.modifiedAt));
    const offset = Number(params.offset || 0);
    const limit = Number(params.limit || 40);
    const pageItems = items.slice(offset, offset + limit).map((item) => params.channel === "all" ? { ...item, audioChannels: null } : { ...item });
    return { total: items.length, offset, limit, items: pageItems, hasMore: offset + limit < items.length };
}

function updateMockProgress() {
    const running = queue.find((job) => job.status === "running");
    if (running && running.progress < 96) {
        running.progress += 3;
        runtime.progress = running.progress;
        runtime.etaSeconds = Math.max(5, Math.round((100 - running.progress) * 1.7));
    }
    if (componentTicks >= 0) {
        componentTicks += 1;
        const progress = Math.min(100, componentTicks * 22);
        for (const key of ["whisper_cli", "base_model"]) {
            components[`component.${key}.status`] = progress >= 100 ? "ready" : "downloading";
            components[`component.${key}.progress`] = String(progress);
            const total = Number(components[`component.${key}.bytes_total`]);
            components[`component.${key}.bytes_downloaded`] = String(Math.round(total * progress / 100));
            components[`component.${key}.bytes_per_second`] = progress >= 100 ? "0" : String(key === "whisper_cli" ? 1800000 : 9200000);
        }
        components["transcriber.components.running"] = progress >= 100 ? "0" : "1";
        components["transcriber.components.state"] = progress >= 100 ? "ready" : "downloading";
        if (progress >= 100) componentTicks = -1;
    }
}

export async function mockSnapshot(view, params) {
    updateMockProgress();
    return {
        meta: { "snapshot.view": view, "snapshot.generated_at": new Date().toISOString() },
        status: { ...status },
        recordingLog: (MOCK_SCENARIO === "empty" ? [] : recordings.slice(0, 16)).map((item, index) => ({
            timestamp: item.modifiedAt,
            output: item.path,
            duration: 34 + index * 8,
            direction: index % 2 ? "outgoing" : "incoming",
            channels: item.audioChannels,
            result: "saved",
        })),
        transcriber: view === "transcriber" || view === "diagnostics" ? {
            dependencies: {
                whisperPath: components["component.whisper_cli.path"], whisperExists: true, whisperExecutable: true,
                modelPath: components["component.base_model.path"], modelExists: true,
                tinydiarizeModelPath: components["component.tinydiarize_model.path"], tinydiarizeModelExists: true,
                readyForStereo: components["component.whisper_cli.status"] === "ready" && components["component.base_model.status"] === "ready",
                readyForMonoDiarization: components["component.whisper_cli.status"] === "ready" && components["component.tinydiarize_model.status"] === "ready",
            },
            outputDir, transcriptDir, queue: { jobs: queue.map((job) => ({ ...job })) }, runtime: { ...runtime }, paused: runtime.state === "paused", stopRequested: runtime.state === "stopping",
        } : null,
        components: view === "transcriber" || view === "diagnostics" ? { ...components } : {},
        recordings: view === "library" || view === "transcriber" ? recordingPage(params) : null,
        raw: "Mock snapshot",
    };
}

function stripShellQuote(value) {
    const trimmed = String(value || "").trim();
    if (trimmed.startsWith("'") && trimmed.endsWith("'")) return trimmed.slice(1, -1).replace(/'\\''/g, "'");
    return trimmed;
}

export async function mockRun(command) {
    for (const part of command.split("&&")) {
        const configMatch = part.match(/config set ([^\s]+) (.+)$/);
        if (configMatch) status[configMatch[1]] = stripShellQuote(configMatch[2]);
    }
    if (command.includes("transcriber enqueue")) {
        const paths = [...command.matchAll(/'([^']+\.(?:wav|m4a|ogg|opus|aac))'/gi)].map((match) => match[1]);
        for (const path of paths) {
            const source = recordings.find((item) => item.path === path);
            if (!source || queue.some((job) => job.recordingPath === path && ["queued", "running"].includes(job.status))) continue;
            queue.push({
                id: `mock-${Date.now()}-${queue.length}`, recordingPath: path, transcriptPath: source.selectedTranscriptPath,
                language: status["transcriber.language"], speakerSelfName: status["transcriber.speaker_self_name"], speakerRemoteName: status["transcriber.speaker_remote_name"],
                format: status["transcriber.output_format"], overwrite: command.includes(" overwrite "), status: "queued", progress: 0, stage: "queued",
                createdAt: new Date().toISOString(), startedAt: null, completedAt: null, error: null, audioChannels: source.audioChannels, diarizationMode: null,
            });
        }
        return "queued=1";
    }
    if (command.includes("transcriber start-worker") || command.includes("transcriber resume")) {
        const next = queue.find((job) => job.status === "queued");
        if (next) {
            next.status = "running"; next.stage = "transcribing"; next.startedAt = new Date().toISOString();
            runtime = { state: "running", stage: "transcribing", activeJobId: next.id, activeFile: next.recordingPath, progress: next.progress, etaSeconds: 120, diarizationMode: next.audioChannels >= 2 ? "stereo" : "tinydiarize", error: null };
        }
        return "started=1";
    }
    if (command.includes("transcriber pause")) {
        const active = queue.find((job) => job.status === "running");
        if (active) { active.status = "queued"; active.progress = 0; active.stage = "queued"; active.startedAt = null; }
        runtime = { state: "paused", stage: "paused", activeJobId: null, activeFile: null, progress: 0, etaSeconds: null, diarizationMode: null, error: null };
        return "paused=1";
    }
    if (command.includes("transcriber stop")) {
        const active = queue.find((job) => job.status === "running");
        if (active) { active.status = "cancelled"; active.stage = "cancelled"; active.completedAt = new Date().toISOString(); }
        runtime = { state: "stopped", stage: "stopped", activeJobId: null, activeFile: null, progress: 0, etaSeconds: null, diarizationMode: null, error: null };
        return "stopped=1";
    }
    if (command.includes("transcriber clear")) {
        queue = queue.filter((job) => ["queued", "running"].includes(job.status));
        return "cleared=1";
    }
    const controlMatch = command.match(/transcriber (remove|retry|move-up|move-down) '?([^'\s]+)'?/);
    if (controlMatch) {
        const [, action, id] = controlMatch;
        const index = queue.findIndex((job) => job.id === id);
        if (action === "remove" && index >= 0 && queue[index].status !== "running") queue.splice(index, 1);
        if (action === "retry" && index >= 0) Object.assign(queue[index], { status: "queued", stage: "queued", progress: 0, startedAt: null, completedAt: null, error: null });
        const delta = action === "move-up" ? -1 : action === "move-down" ? 1 : 0;
        if (delta && index >= 0 && queue[index + delta]?.status === "queued") [queue[index], queue[index + delta]] = [queue[index + delta], queue[index]];
        return "updated=1";
    }
    if (command.includes("transcriber install-deps")) {
        componentTicks = 0;
        components["transcriber.components.running"] = "1";
        components["transcriber.components.state"] = "downloading";
        return "Component preparation started";
    }
    const removeComponent = command.match(/transcriber remove-component ([^\s]+)/);
    if (removeComponent) {
        const key = removeComponent[1];
        components[`component.${key}.status`] = "missing";
        components[`component.${key}.progress`] = "0";
        components[`component.${key}.bytes_downloaded`] = "0";
        return `removed.component=${key}`;
    }
    if (command.includes("transcriber remove-deps")) {
        for (const key of ["whisper_cli", "base_model", "tinydiarize_model"]) components[`component.${key}.status`] = "missing";
        return "removed=1";
    }
    if (command.includes("transcriber preview")) {
        return JSON.stringify({ available: true, truncated: false, text: "BCR Headless Transcript\n\n[00:00:01.200 - 00:00:03.500] Speaker A: Hello, can you hear me?\n[00:00:03.800 - 00:00:05.900] Speaker B: Yes, I can hear you clearly.\n[00:00:06.400 - 00:00:09.200] Speaker A: Great. This preview keeps the conversation in timestamp order." });
    }
    if (command.includes(" probe")) return "VOICE_CALL stereo initialization: supported\nAudio source: VOICE_CALL\nChannels: 2\nResult: ready";
    if (command.includes(" logs") || command.endsWith("logs")) return "[mock] Recorder daemon ready\n[mock] No recent errors\n[mock] Transcriber worker active";
    return "ok=1";
}

import { exec, moduleInfo } from "./kernelsu.js";
import { mockRun, mockSnapshot } from "./mock-api.js";

const DEFAULT_MODULE_ID = "bcr.headless";
export const IS_MOCK = new URLSearchParams(window.location.search).get("mock") === "1" || typeof window.ksu === "undefined";

function resolveModuleContext() {
    if (IS_MOCK) return { moduleId: DEFAULT_MODULE_ID, moduleDir: `/data/adb/modules/${DEFAULT_MODULE_ID}` };
    try {
        const info = moduleInfo() || {};
        const moduleId = info.moduleId || info.id || DEFAULT_MODULE_ID;
        return { moduleId, moduleDir: info.moduleDir || info.path || info.dir || `/data/adb/modules/${moduleId}` };
    } catch (_) {
        return { moduleId: DEFAULT_MODULE_ID, moduleDir: `/data/adb/modules/${DEFAULT_MODULE_ID}` };
    }
}

export const MODULE = resolveModuleContext();

export function shellQuote(value) {
    return `'${String(value).replace(/'/g, `'\\''`)}'`;
}

export function parseKeyValues(text) {
    const values = {};
    for (const line of String(text || "").split(/\r?\n/)) {
        const index = line.indexOf("=");
        if (index <= 0) continue;
        values[line.slice(0, index)] = line.slice(index + 1);
    }
    return values;
}

function parseJson(text, fallback) {
    if (!String(text || "").trim()) return fallback;
    try { return JSON.parse(text); } catch (_) { return fallback; }
}

function parseSnapshot(text) {
    const sections = {};
    let current = "";
    for (const line of String(text || "").split(/\r?\n/)) {
        if (line.startsWith("@@")) {
            current = line.slice(2).trim();
            if (current !== "END") sections[current] = [];
            continue;
        }
        if (current && current !== "END") sections[current].push(line);
    }
    const content = (name) => (sections[name] || []).join("\n").trim();
    return {
        meta: parseKeyValues(content("META")),
        status: parseKeyValues(content("STATUS")),
        recordingLog: parseJson(content("RECORDING_LOG"), []),
        transcriber: parseJson(content("TRANSCRIBER"), null),
        components: parseKeyValues(content("COMPONENTS")),
        recordings: parseJson(content("RECORDINGS"), null),
        raw: String(text || ""),
    };
}

async function execCommand(command) {
    return exec(command, { cwd: MODULE.moduleDir, env: { KSU_MODULE: MODULE.moduleId } });
}

export async function run(command) {
    if (IS_MOCK) return mockRun(command);
    const result = await execCommand(command);
    if (result.errno !== 0) {
        throw new Error([result.stderr, result.stdout].filter(Boolean).join("\n") || `Command failed (${result.errno})`);
    }
    return String(result.stdout || "").trim();
}

export async function runCapture(command) {
    try {
        const stdout = await run(command);
        return { ok: true, command, stdout, stderr: "", errno: 0 };
    } catch (error) {
        return { ok: false, command, stdout: "", stderr: String(error.message || error), errno: -1 };
    }
}

export async function getSnapshot(view, params = {}) {
    const normalized = {
        offset: Number(params.offset || 0),
        limit: Number(params.limit || 40),
        search: params.search || "",
        transcript: params.transcript || "all",
        sort: params.sort || "newest",
        channel: params.channel || "all",
    };
    if (IS_MOCK) return mockSnapshot(view, normalized);
    const command = [
        "sh ./action.sh ui-snapshot",
        view,
        normalized.offset,
        normalized.limit,
        shellQuote(normalized.search),
        shellQuote(normalized.transcript),
        shellQuote(normalized.sort),
        shellQuote(normalized.channel),
    ].join(" ");
    return parseSnapshot(await run(command));
}

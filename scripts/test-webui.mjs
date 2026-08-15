/* SPDX-License-Identifier: GPL-3.0-only */

import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { MOCK_SCENARIO, mockRun, mockSnapshot } from "../app/magisk/webroot/mock-api.js";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const webroot = path.join(root, "app", "magisk", "webroot");
const html = fs.readFileSync(path.join(webroot, "index.html"), "utf8");
const app = fs.readFileSync(path.join(webroot, "app.js"), "utf8");

const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
assert.equal(new Set(ids).size, ids.length, "index.html contains duplicate IDs");
for (const required of [
    "recorder-view", "library-view", "transcribe-view", "diagnostics-view",
    "library-list", "queue-list", "component-list", "save-bar", "action-error", "choice-dialog", "preview-dialog",
]) {
    assert.ok(ids.includes(required), `Missing required WebUI element: ${required}`);
}
assert.ok(!app.includes("innerHTML"), "Dynamic data must not be rendered with innerHTML");
assert.ok(!app.includes("setInterval"), "Polling must use one-shot timers to avoid overlapping root calls");
assert.equal(MOCK_SCENARIO, "default");
for (const scenario of ["empty", "download", "failure", "debug"]) {
    assert.ok(fs.readFileSync(path.join(webroot, "mock-api.js"), "utf8").includes(`MOCK_SCENARIO === "${scenario}"`));
}

const library = await mockSnapshot("library", { offset: 0, limit: 20, search: "", transcript: "all", sort: "newest", channel: "all" });
assert.equal(library.recordings.items.length, 20);
assert.equal(library.recordings.offset, 0);
assert.ok(library.recordings.hasMore);

const filtered = await mockSnapshot("library", { offset: 0, limit: 20, search: "", transcript: "ready", sort: "newest", channel: "mono" });
assert.ok(filtered.recordings.items.every((item) => item.selectedTranscriptExists && item.audioChannels === 1));

await mockRun("sh ./action.sh transcriber pause");
const paused = await mockSnapshot("transcriber", { offset: 0, limit: 20 });
assert.equal(paused.transcriber.runtime.state, "paused");
assert.ok(paused.transcriber.queue.jobs.every((job) => job.status !== "running"));

console.log("WebUI contract checks passed");

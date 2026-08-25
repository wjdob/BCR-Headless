const VIEW_KEY = "bcr-headless.active-view";
const SELECTION_KEY = "bcr-headless.recording-selection";

function loadSelection() {
    try {
        const values = JSON.parse(localStorage.getItem(SELECTION_KEY) || "[]");
        return new Set(Array.isArray(values) ? values : []);
    } catch (_) {
        return new Set();
    }
}

export const appState = {
    activeView: localStorage.getItem(VIEW_KEY) || "recorder",
    status: {},
    transcriber: null,
    components: {},
    recordings: { total: 0, offset: 0, limit: 40, items: [], hasMore: false },
    recordingLog: [],
    selectedRecordings: loadSelection(),
    dirtySections: new Set(),
    latestSnapshots: new Map(),
    requestSerial: 0,
    pollTimer: null,
    pollInFlight: false,
    componentPreparing: false,
    currentPreviewPath: "",
    filters: {
        library: { offset: 0, limit: 40, search: "", transcript: "all", sort: "newest", channel: "all" },
        transcriber: { offset: 0, limit: 20, search: "", transcript: "all", sort: "newest", channel: "all" },
    },
};

export function rememberView(view) {
    appState.activeView = view;
    localStorage.setItem(VIEW_KEY, view);
}

export function rememberSelection() {
    localStorage.setItem(SELECTION_KEY, JSON.stringify([...appState.selectedRecordings]));
}

export function markDirty(section) {
    appState.dirtySections.add(section);
}

export function clearDirty() {
    appState.dirtySections.clear();
}

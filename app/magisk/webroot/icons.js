// Curated Lucide icon paths. Lucide is licensed under the ISC License.
const ICONS = {
    "phone-call": [["path", { d: "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6A19.79 19.79 0 0 1 2.12 4.18 2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" }], ["path", { d: "M14.5 2a5.5 5.5 0 0 1 5.5 5.5" }]],
    mic: [["path", { d: "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z" }], ["path", { d: "M19 10v2a7 7 0 0 1-14 0v-2" }], ["line", { x1: "12", y1: "19", x2: "12", y2: "22" }]],
    library: [["path", { d: "m16 6 4 14" }], ["path", { d: "M12 6v14" }], ["path", { d: "M8 8v12" }], ["path", { d: "M4 4v16" }]],
    captions: [["rect", { x: "2", y: "5", width: "20", height: "14", rx: "2" }], ["path", { d: "M7 15h4m2 0h4M7 11h2m4 0h4" }]],
    activity: [["path", { d: "M22 12h-4l-3 9L9 3l-3 9H2" }]],
    "refresh-cw": [["path", { d: "M21 12a9 9 0 0 0-15-6.7L3 8" }], ["path", { d: "M3 3v5h5" }], ["path", { d: "M3 12a9 9 0 0 0 15 6.7L21 16" }], ["path", { d: "M16 16h5v5" }]],
    "refresh-ccw": [["path", { d: "M3 12a9 9 0 0 1 15-6.7L21 8" }], ["path", { d: "M21 3v5h-5" }], ["path", { d: "M21 12a9 9 0 0 1-15 6.7L3 16" }], ["path", { d: "M8 16H3v5" }]],
    "rotate-ccw": [["path", { d: "M3 12a9 9 0 1 0 3-6.7L3 8" }], ["path", { d: "M3 3v5h5" }]],
    "rotate-cw": [["path", { d: "M21 12a9 9 0 1 1-3-6.7L21 8" }], ["path", { d: "M21 3v5h-5" }]],
    search: [["circle", { cx: "11", cy: "11", r: "8" }], ["path", { d: "m21 21-4.3-4.3" }]],
    "folder-open": [["path", { d: "m6 14 1.5-3h13l-2 8H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h5a2 2 0 0 1 2 2v1" }]],
    "list-plus": [["path", { d: "M11 12H3m8-6H3m8 12H3" }], ["path", { d: "M18 9v6m-3-3h6" }]],
    "list-x": [["path", { d: "M11 12H3m8-6H3m8 12H3" }], ["path", { d: "m17 9 5 5m0-5-5 5" }]],
    "chevron-left": [["path", { d: "m15 18-6-6 6-6" }]],
    "chevron-right": [["path", { d: "m9 18 6-6-6-6" }]],
    "chevron-down": [["path", { d: "m6 9 6 6 6-6" }]],
    play: [["polygon", { points: "6 3 20 12 6 21 6 3" }]],
    pause: [["rect", { x: "6", y: "4", width: "4", height: "16" }], ["rect", { x: "14", y: "4", width: "4", height: "16" }]],
    square: [["rect", { x: "5", y: "5", width: "14", height: "14", rx: "1" }]],
    download: [["path", { d: "M12 3v12m-5-5 5 5 5-5" }], ["path", { d: "M5 21h14" }]],
    settings: [["path", { d: "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z" }], ["path", { d: "M19.4 15a1.7 1.7 0 0 0 .34 1.88l.06.06-2.83 2.83-.06-.06A1.7 1.7 0 0 0 15 19.4a1.7 1.7 0 0 0-1 .6 1.7 1.7 0 0 0-.4 1.1V21h-4v-.1A1.7 1.7 0 0 0 8.6 19.4a1.7 1.7 0 0 0-1.88.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-.6-1 1.7 1.7 0 0 0-1.1-.4H3v-4h.1A1.7 1.7 0 0 0 4.6 8.6a1.7 1.7 0 0 0-.34-1.88l-.06-.06 2.83-2.83.06.06A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-.6 1.7 1.7 0 0 0 .4-1.1V3h4v.1A1.7 1.7 0 0 0 15.4 4.6a1.7 1.7 0 0 0 1.88-.34l.06-.06 2.83 2.83-.06.06A1.7 1.7 0 0 0 19.4 9c.14.38.35.72.64 1 .3.28.68.43 1.08.4H21v4h-.1a1.7 1.7 0 0 0-1.5.6Z" }]],
    stethoscope: [["path", { d: "M11 2v2M5 2v2M5 3H3v5a5 5 0 0 0 10 0V3h-2" }], ["path", { d: "M9 13a5 5 0 0 0 10 0v-1" }], ["circle", { cx: "19", cy: "10", r: "2" }]],
    "scroll-text": [["path", { d: "M8 21h12a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2H8" }], ["path", { d: "M16 17H4a2 2 0 0 0-2 2 2 2 0 0 0 2 2h4V5a2 2 0 0 0-2-2 2 2 0 0 0-2 2v12" }], ["path", { d: "M12 7h6m-6 4h6m-6 4h4" }]],
    save: [["path", { d: "M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2Z" }], ["path", { d: "M17 21v-8H7v8M7 3v5h8" }]],
    x: [["path", { d: "M18 6 6 18M6 6l12 12" }]],
    "external-link": [["path", { d: "M15 3h6v6m0-6-9 9" }], ["path", { d: "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6" }]],
    "trash-2": [["path", { d: "M3 6h18m-3 0v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2v2m-5 5v6m4-6v6" }]],
    eye: [["path", { d: "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" }], ["circle", { cx: "12", cy: "12", r: "3" }]],
    "file-text": [["path", { d: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z" }], ["path", { d: "M14 2v6h6M8 13h8m-8 4h8" }]],
    "arrow-up": [["path", { d: "m18 15-6-6-6 6" }]],
    "arrow-down": [["path", { d: "m6 9 6 6 6-6" }]],
    wrench: [["path", { d: "M14.7 6.3a4 4 0 0 0-5-5l2.1 2.1-2.4 2.4-2.1-2.1a4 4 0 0 0 5 5L20 16.4a2.1 2.1 0 0 1-3 3l-7.7-7.7" }]],
    "check-circle": [["path", { d: "M22 11.1V12a10 10 0 1 1-5.9-9.1" }], ["path", { d: "m9 11 3 3L22 4" }]],
    "alert-circle": [["circle", { cx: "12", cy: "12", r: "10" }], ["path", { d: "M12 8v4m0 4h.01" }]],
    clock: [["circle", { cx: "12", cy: "12", r: "9" }], ["path", { d: "M12 7v5l3 2" }]],
    "audio-lines": [["path", { d: "M2 10v4m4-7v10m4-13v16m4-13v10m4-7v4m4-2v0" }]],
};

export function createIcon(name) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    for (const [tag, attributes] of ICONS[name] || ICONS["alert-circle"]) {
        const child = document.createElementNS("http://www.w3.org/2000/svg", tag);
        for (const [key, value] of Object.entries(attributes)) {
            child.setAttribute(key, value);
        }
        svg.appendChild(child);
    }
    return svg;
}

export function renderIcons(root = document) {
    for (const placeholder of root.querySelectorAll("[data-icon]")) {
        if (placeholder.dataset.iconRendered === "1") {
            continue;
        }
        placeholder.replaceChildren(createIcon(placeholder.dataset.icon));
        placeholder.dataset.iconRendered = "1";
    }
}

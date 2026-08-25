import { createIcon, renderIcons } from "./icons.js";

export function element(tag, options = {}, children = []) {
    const node = document.createElement(tag);
    if (options.className) node.className = options.className;
    if (options.text !== undefined) node.textContent = String(options.text);
    if (options.title) node.title = options.title;
    if (options.type) node.type = options.type;
    for (const [key, value] of Object.entries(options.attributes || {})) {
        if (value !== null && value !== undefined) node.setAttribute(key, String(value));
    }
    for (const [key, value] of Object.entries(options.dataset || {})) {
        node.dataset[key] = String(value);
    }
    for (const child of Array.isArray(children) ? children : [children]) {
        if (child === null || child === undefined) continue;
        node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
}

export function iconButton(icon, label, className = "icon-button secondary") {
    const button = element("button", {
        className,
        type: "button",
        title: label,
        attributes: { "aria-label": label },
    }, createIcon(icon));
    return button;
}

export function badge(text, tone = "neutral") {
    return element("span", { className: `badge badge-${tone}`, text });
}

export function setPending(button, pending, pendingLabel = "Working") {
    if (!button) return;
    if (pending) {
        button.disabled = true;
        button.setAttribute("aria-busy", "true");
        button.dataset.pendingLabel = pendingLabel;
    } else {
        button.disabled = false;
        button.removeAttribute("aria-busy");
        delete button.dataset.pendingLabel;
    }
}

export function showToast(message, tone = "normal", timeout = 4200) {
    const region = document.querySelector("#toast-region");
    if (!region) return;
    const toast = element("div", {
        className: `toast-message${tone === "error" ? " is-error" : ""}`,
        text: message,
    });
    region.appendChild(toast);
    window.setTimeout(() => toast.remove(), timeout);
}

export function showListState(container, message, kind = "empty") {
    container.replaceChildren(element("div", { className: `${kind}-state`, text: message }));
}

export function requestChoice({ title, message, checkboxLabel = "", actions = [] }) {
    const dialog = document.querySelector("#choice-dialog");
    const titleNode = document.querySelector("#choice-dialog-title");
    const messageNode = document.querySelector("#choice-dialog-message");
    const checkboxRow = document.querySelector("#choice-dialog-checkbox-row");
    const checkbox = document.querySelector("#choice-dialog-checkbox");
    const checkboxText = document.querySelector("#choice-dialog-checkbox-label");
    const actionContainer = document.querySelector("#choice-dialog-actions");
    titleNode.textContent = title;
    messageNode.textContent = message;
    checkbox.checked = false;
    checkboxRow.hidden = !checkboxLabel;
    checkboxText.textContent = checkboxLabel;
    actionContainer.replaceChildren();

    return new Promise((resolve) => {
        let settled = false;
        const finish = (value) => {
            if (settled) return;
            settled = true;
            if (dialog.open) dialog.close();
            resolve({ value, checked: checkbox.checked });
        };
        for (const action of actions) {
            const button = element("button", {
                className: action.className || "text-button",
                text: action.label,
                type: "button",
            });
            button.addEventListener("click", () => finish(action.value));
            actionContainer.appendChild(button);
        }
        const onClose = () => {
            dialog.removeEventListener("close", onClose);
            if (!settled) finish("cancel");
        };
        dialog.addEventListener("close", onClose);
        dialog.showModal();
    });
}

export { renderIcons };

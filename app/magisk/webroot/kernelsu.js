// Adapted from the official KernelSU WebUI bridge package so the module page
// remains fully self-contained and does not depend on network access.

let callbackCounter = 0;

function getUniqueCallbackName(prefix) {
    return `${prefix}_callback_${Date.now()}_${callbackCounter++}`;
}

export function exec(command, options = {}) {
    return new Promise((resolve, reject) => {
        const callbackFuncName = getUniqueCallbackName("exec");

        window[callbackFuncName] = (errno, stdout, stderr) => {
            resolve({ errno, stdout, stderr });
            delete window[callbackFuncName];
        };

        try {
            ksu.exec(command, JSON.stringify(options), callbackFuncName);
        } catch (error) {
            delete window[callbackFuncName];
            reject(error);
        }
    });
}

export function toast(message) {
    ksu.toast(message);
}

export function moduleInfo() {
    // KernelSU exposes module metadata synchronously. Keeping a tiny local
    // wrapper lets the page discover its own id/path instead of baking those
    // values into the frontend.
    return ksu.moduleInfo();
}

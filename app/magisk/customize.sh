#!/system/bin/sh

# SPDX-FileCopyrightText: 2024 Harin Lee
# SPDX-License-Identifier: GPL-3.0-only

# Delete addon.d script when installing as Magisk module to prevent
# update_engine from executing it on A/B devices.
copy_tree_preserve() {
    src="${1}"
    dest="${2}"

    [ -d "${src}" ] || return 0
    mkdir -p "${dest}"

    if command -v cp >/dev/null 2>&1; then
        cp -af "${src}/." "${dest}/" 2>/dev/null && return 0
    fi

    if command -v tar >/dev/null 2>&1; then
        (cd "${src}" && tar -cf - .) | (cd "${dest}" && tar -xf -) 2>/dev/null && return 0
    fi

    return 1
}

copy_file_preserve() {
    src="${1}"
    dest="${2}"

    [ -f "${src}" ] || return 0
    mkdir -p "${dest%/*}"
    cat "${src}" > "${dest}" 2>/dev/null || return 1
    return 0
}

preserve_existing_state() {
    [ -n "${MODPATH}" ] || return 0
    module_prop_file="${MODPATH}/module.prop"
    [ -f "${module_prop_file}" ] || return 0

    module_id=$(grep '^id=' "${module_prop_file}" 2>/dev/null | cut -d= -f2-)
    [ -n "${module_id}" ] || return 0

    modules_root="${BCR_MODULES_DIR:-/data/adb/modules}"
    current_modpath="${modules_root}/${module_id}"
    [ -d "${current_modpath}" ] || return 0
    [ "${current_modpath}" != "${MODPATH}" ] || return 0

    copy_tree_preserve "${current_modpath}/.config" "${MODPATH}/.config" || true
    copy_tree_preserve "${current_modpath}/tools/transcriber" "${MODPATH}/tools/transcriber" || true

    for state_file in \
        recording-log.json \
        transcriber-queue.json \
        transcriber-runtime.json \
        transcriber-components.env \
        transcriber-tools.env \
        daemon.log \
        transcriber.log; do
        copy_file_preserve "${current_modpath}/.state/${state_file}" "${MODPATH}/.state/${state_file}" || true
    done

    rm -f \
        "${MODPATH}/.state/daemon.pid" \
        "${MODPATH}/.state/runtime.env" \
        "${MODPATH}/.state/transcriber.pid" \
        "${MODPATH}/.state/transcriber.stop" \
        "${MODPATH}/.state/transcriber.pause" \
        "${MODPATH}/.state/transcriber-components.pid"
}

[ -n "$MODPATH" ] && preserve_existing_state
[ -n "$MODPATH" ] && rm -r "$MODPATH/system/addon.d"

# SPDX-FileCopyrightText: 2023 Andrew Gunnerson
# SPDX-License-Identifier: GPL-3.0-only
#
# source "${0%/*}/boot_common.sh" <log file>

mod_dir=${0%/*}
state_dir="${mod_dir}/.state"

is_prop_enabled() {
    [ "$(getprop "${1}")" = "1" ]
}

is_boot_debug_enabled() {
    # Keep boot-time diagnostics opt-in. Writing logs to /data/local/tmp and
    # collecting logcat/dumpsys output adds a very visible root footprint that
    # basic call recording itself does not need.
    is_prop_enabled persist.bcr.debug_boot
}

if is_boot_debug_enabled; then
    exec >"${1}" 2>&1
else
    exec >/dev/null 2>&1
fi

header() {
    echo "----- ${*} -----"
}

module_prop() {
    grep "^${1}=" "${mod_dir}/module.prop" | cut -d= -f2
}

run_cli_apk() {
    CLASSPATH="${cli_apk}" app_process / "${@}" &
    pid=${!}
    wait "${pid}"
    status=${?}

    if is_boot_debug_enabled; then
        echo "Exit status: ${status}"
        echo "Logcat:"
        logcat -d --pid "${pid}"
    fi

    return "${status}"
}

ensure_state_dir() {
    # Persist one-shot markers inside the module directory so boot scripts can
    # skip privileged maintenance once a given module version has already done it.
    mkdir -p "${state_dir}"
}

state_marker_path() {
    echo "${state_dir}/${1}-${app_version}"
}

should_run_once_per_version() {
    ensure_state_dir
    [ ! -e "$(state_marker_path "${1}")" ]
}

mark_ran_for_version() {
    ensure_state_dir
    touch "$(state_marker_path "${1}")"
}

app_id=$(module_prop id)
app_version=$(module_prop version)
cli_apk=$(echo "${mod_dir}"/system/priv-app/"${app_id}"/app-*.apk)

header Environment
echo "Timestamp: $(date)"
echo "Script: ${0}"
echo "App ID: ${app_id}"
echo "App version: ${app_version}"
echo "CLI APK: ${cli_apk}"
echo "UID/GID/Context: $(id)"

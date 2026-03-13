#!/system/bin/sh

# SPDX-FileCopyrightText: 2026 wjdob
# SPDX-License-Identifier: GPL-3.0-only

mod_dir=${0%/*}
state_dir="${mod_dir}/.state"
config_dir="${mod_dir}/.config"
pid_file="${state_dir}/daemon.pid"
runtime_file="${state_dir}/runtime.env"
daemon_log="${state_dir}/daemon.log"
recording_log_file="${state_dir}/recording-log.json"
helper_apk="${mod_dir}/tools/bcr-headless.apk"

# Config persistence ---------------------------------------------------------

module_prop() {
    grep "^${1}=" "${mod_dir}/module.prop" | cut -d= -f2-
}

module_id=$(module_prop id)
: "${KSU_MODULE:=${module_id}}"

ensure_dirs() {
    mkdir -p "${state_dir}" "${config_dir}"
}

has_ksud() {
    command -v ksud >/dev/null 2>&1
}

config_get() {
    key="${1}"

    if has_ksud; then
        value=$(ksud module config get "${key}" 2>/dev/null || true)
        if [ -n "${value}" ]; then
            printf '%s' "${value}"
            return 0
        fi
    fi

    if [ -f "${config_dir}/${key}" ]; then
        cat "${config_dir}/${key}"
        return 0
    fi

    return 1
}

config_set() {
    key="${1}"
    value="${2}"

    ensure_dirs

    # Mirror the value into a plain-text store first so the module remains
    # operable on Magisk while still syncing into KernelSU's native config
    # store when that manager is present.
    printf '%s' "${value}" > "${config_dir}/${key}"

    if has_ksud; then
        printf '%s' "${value}" | ksud module config set "${key}" --stdin >/dev/null 2>&1 || true
    fi
}

config_delete() {
    key="${1}"

    rm -f "${config_dir}/${key}"

    if has_ksud; then
        ksud module config delete "${key}" >/dev/null 2>&1 || true
    fi
}

config_list() {
    keys="recording.enabled output.dir recording.min_duration recording.log_enabled notifications.enabled override.description"

    for key in ${keys}; do
        if value=$(config_get "${key}" 2>/dev/null); then
            printf '%s=%s\n' "${key}" "${value}"
        fi
    done
}

config_get_or_default() {
    key="${1}"
    default="${2}"

    if value=$(config_get "${key}"); then
        printf '%s' "${value}"
    else
        printf '%s' "${default}"
    fi
}

is_enabled_value() {
    case "$(printf '%s' "${1}" | tr '[:upper:]' '[:lower:]')" in
        1|true|yes|on)
            return 0
            ;;
        *)
            return 1
            ;;
    esac
}

config_is_enabled() {
    value=$(config_get_or_default "${1}" "${2}")
    is_enabled_value "${value}"
}

bool_string() {
    if "${@}"; then
        printf '1'
    else
        printf '0'
    fi
}

# Defaults -------------------------------------------------------------------

ensure_defaults() {
    # Pick conservative defaults that keep the module inert until the user opts
    # in from the WebUI. That avoids starting call-state monitoring immediately
    # after flashing the module.
    if ! config_get recording.enabled >/dev/null 2>&1; then
        config_set recording.enabled 0
    fi
    if ! config_get output.dir >/dev/null 2>&1; then
        config_set output.dir /sdcard/Recordings/BCR
    fi
    if ! config_get recording.min_duration >/dev/null 2>&1; then
        config_set recording.min_duration 0
    fi
    if ! config_get recording.log_enabled >/dev/null 2>&1; then
        if legacy_log_enabled=$(config_get output.write_metadata 2>/dev/null); then
            # Migrate the old JSON-sidecar toggle into the new in-module
            # recording history toggle so existing installs preserve intent.
            config_set recording.log_enabled "${legacy_log_enabled}"
        else
            config_set recording.log_enabled 1
        fi
    fi
    if ! config_get notifications.enabled >/dev/null 2>&1; then
        # Headless mode benefits from lightweight notifications so the user can
        # confirm that recording is active.
        config_set notifications.enabled 1
    fi
}

reset_defaults() {
    # Drop the explicit config files and reapply the documented defaults so the
    # WebUI and the daemon always converge back to the same baseline values.
    for key in recording.enabled output.dir recording.min_duration recording.log_enabled notifications.enabled; do
        config_delete "${key}"
    done

    ensure_defaults
}

# Runtime state ---------------------------------------------------------------

runtime_get() {
    key="${1}"
    [ -f "${runtime_file}" ] || return 1

    awk -F= -v target="${key}" '$1 == target { value = substr($0, length($1) + 2) } END { if (value != "") print value }' "${runtime_file}"
}

is_daemon_running() {
    [ -f "${pid_file}" ] || return 1

    pid=$(cat "${pid_file}" 2>/dev/null || true)
    [ -n "${pid}" ] || return 1

    kill -0 "${pid}" 2>/dev/null
}

clear_stale_pid() {
    if ! is_daemon_running; then
        rm -f "${pid_file}"
    fi
}

# Manager-facing helpers ------------------------------------------------------

refresh_description() {
    ensure_dirs

    enabled=$(bool_string config_is_enabled recording.enabled 0)
    running=$(bool_string is_daemon_running)
    output_dir=$(config_get_or_default output.dir /sdcard/Recordings/BCR)
    runtime_state=$(runtime_get recorder.state 2>/dev/null || true)

    if [ -z "${runtime_state}" ]; then
        if [ "${running}" = "1" ]; then
            runtime_state=running
        else
            runtime_state=stopped
        fi
    fi

    # Surface only operational state in the manager UI. The old system-app
    # approach exposed package identity and permissions; the headless path keeps
    # the module visible only through manager-owned state.
    description="Headless ${runtime_state} | enabled=${enabled} | output=${output_dir}"

    if has_ksud; then
        config_set override.description "${description}"
    fi
}

# Daemon lifecycle ------------------------------------------------------------

run_helper_foreground() {
    if [ ! -f "${helper_apk}" ]; then
        echo "Missing helper APK: ${helper_apk}" >&2
        return 1
    fi

    CLASSPATH="${helper_apk}" app_process / com.chiller3.bcr.headless.HeadlessMain "${@}"
}

ensure_recording_log_exists() {
    ensure_dirs

    if [ ! -f "${recording_log_file}" ]; then
        printf '[]\n' > "${recording_log_file}"
    fi
}

print_recording_log() {
    ensure_recording_log_exists
    cat "${recording_log_file}"
}

clear_recording_log() {
    ensure_recording_log_exists
    printf '[]\n' > "${recording_log_file}"
}

start_daemon() {
    ensure_dirs
    ensure_defaults
    clear_stale_pid

    if is_daemon_running; then
        refresh_description
        return 0
    fi

    if ! config_is_enabled recording.enabled 0; then
        # Clear stale runtime state when recording is disabled so the WebUI does
        # not keep showing old monitor fields from a previous daemon run.
        rm -f "${runtime_file}"
        refresh_description
        return 0
    fi

    if [ ! -f "${helper_apk}" ]; then
        echo "Missing helper APK: ${helper_apk}" >&2
        refresh_description
        return 1
    fi

    output_dir=$(config_get_or_default output.dir /sdcard/Recordings/BCR)
    min_duration=$(config_get_or_default recording.min_duration 0)
    log_enabled=$(bool_string config_is_enabled recording.log_enabled 1)
    notifications_enabled=$(bool_string config_is_enabled notifications.enabled 1)

    : > "${daemon_log}"

    # Run from the module directory instead of installing anything into /system
    # or PackageManager. The daemon's code lives in the helper APK under tools/.
    CLASSPATH="${helper_apk}" app_process / \
        com.chiller3.bcr.headless.HeadlessMain \
        daemon "${mod_dir}" "${output_dir}" "${min_duration}" "${log_enabled}" "${notifications_enabled}" \
        >>"${daemon_log}" 2>&1 &
    echo "${!}" > "${pid_file}"

    sleep 1
    clear_stale_pid
    refresh_description
}

stop_daemon() {
    if is_daemon_running; then
        pid=$(cat "${pid_file}")
        kill "${pid}" 2>/dev/null || true
        sleep 1
        kill -9 "${pid}" 2>/dev/null || true
    fi

    rm -f "${pid_file}"
    rm -f "${runtime_file}"
    refresh_description
}

print_status() {
    ensure_dirs
    clear_stale_pid

    echo "module.id=${module_id}"
    echo "module.path=${mod_dir}"
    echo "recording.enabled=$(bool_string config_is_enabled recording.enabled 0)"
    echo "output.dir=$(config_get_or_default output.dir /sdcard/Recordings/BCR)"
    echo "recording.min_duration=$(config_get_or_default recording.min_duration 0)"
    echo "recording.log_enabled=$(bool_string config_is_enabled recording.log_enabled 1)"
    echo "notifications.enabled=$(bool_string config_is_enabled notifications.enabled 1)"
    echo "daemon.running=$(bool_string is_daemon_running)"
    echo "daemon.pid=$(cat "${pid_file}" 2>/dev/null || true)"

    if [ -f "${runtime_file}" ]; then
        cat "${runtime_file}"
    fi
}

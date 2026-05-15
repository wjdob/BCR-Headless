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
transcriber_pid_file="${state_dir}/transcriber.pid"
transcriber_runtime_file="${state_dir}/transcriber-runtime.json"
transcriber_queue_file="${state_dir}/transcriber-queue.json"
transcriber_log="${state_dir}/transcriber.log"
transcriber_stop_file="${state_dir}/transcriber.stop"
transcriber_pause_file="${state_dir}/transcriber.pause"
transcriber_components_file="${state_dir}/transcriber-components.env"
transcriber_components_pid_file="${state_dir}/transcriber-components.pid"
transcriber_tools_dir="${mod_dir}/tools/transcriber"
default_transcriber_tools_release_base_url="https://github.com/wjdob/BCR-Headless/releases/download/transcriber-tools"
default_whisper_manifest_url="${default_transcriber_tools_release_base_url}/transcriber-tools.env"
legacy_default_whisper_url="https://github.com/wjdob/BCR-Headless/releases/download/transcriber-tools/whisper-cli-android-arm64.zip"
default_whisper_url=""
default_model_url="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"
default_tinydiarize_model_url="https://huggingface.co/akashmjn/tinydiarize-whisper.cpp/resolve/main/ggml-small.en-tdrz.bin"
default_whisper_size=16777216
default_model_size=147964211
default_tinydiarize_model_size=487614184

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
    keys="recording.enabled output.dir recording.min_duration recording.log_enabled recording.stereo notifications.enabled transcriber.enabled transcriber.output_dir transcriber.language transcriber.output_format transcriber.whisper_path transcriber.model_path transcriber.tinydiarize_model_path transcriber.whisper_manifest_url transcriber.whisper_url transcriber.model_url transcriber.tinydiarize_model_url override.description"

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
    if ! config_get recording.stereo >/dev/null 2>&1; then
        config_set recording.stereo 0
    fi
    if ! config_get transcriber.enabled >/dev/null 2>&1; then
        config_set transcriber.enabled 0
    fi
    if ! config_get transcriber.output_dir >/dev/null 2>&1; then
        output_dir=$(config_get_or_default output.dir /sdcard/Recordings/BCR)
        config_set transcriber.output_dir "${output_dir}/transcripts"
    fi
    if ! config_get transcriber.language >/dev/null 2>&1; then
        config_set transcriber.language en
    fi
    if ! config_get transcriber.output_format >/dev/null 2>&1; then
        config_set transcriber.output_format txt
    fi
    if ! config_get transcriber.whisper_path >/dev/null 2>&1; then
        config_set transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli"
    fi
    if ! config_get transcriber.model_path >/dev/null 2>&1; then
        config_set transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin"
    fi
    if ! config_get transcriber.tinydiarize_model_path >/dev/null 2>&1; then
        config_set transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin"
    fi
    if ! config_get transcriber.whisper_url >/dev/null 2>&1; then
        config_set transcriber.whisper_url "${default_whisper_url}"
    fi
    current_whisper_url=$(config_get_or_default transcriber.whisper_url "")
    if [ "${current_whisper_url}" = "${legacy_default_whisper_url}" ]; then
        config_set transcriber.whisper_url "${default_whisper_url}"
    fi
    if ! config_get transcriber.whisper_manifest_url >/dev/null 2>&1; then
        config_set transcriber.whisper_manifest_url "${default_whisper_manifest_url}"
    fi
    current_whisper_manifest_url=$(config_get_or_default transcriber.whisper_manifest_url "")
    if [ -z "${current_whisper_manifest_url}" ]; then
        config_set transcriber.whisper_manifest_url "${default_whisper_manifest_url}"
    fi
    if ! config_get transcriber.model_url >/dev/null 2>&1; then
        config_set transcriber.model_url "${default_model_url}"
    fi
    current_model_url=$(config_get_or_default transcriber.model_url "")
    if [ -z "${current_model_url}" ]; then
        config_set transcriber.model_url "${default_model_url}"
    fi
    if ! config_get transcriber.tinydiarize_model_url >/dev/null 2>&1; then
        config_set transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}"
    fi
    current_tdrz_url=$(config_get_or_default transcriber.tinydiarize_model_url "")
    if [ -z "${current_tdrz_url}" ] || [ "${current_tdrz_url}" = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en-tdrz.bin" ]; then
        config_set transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}"
    fi
}

reset_defaults() {
    # Drop the explicit config files and reapply the documented defaults so the
    # WebUI and the daemon always converge back to the same baseline values.
    for key in recording.enabled output.dir recording.min_duration recording.log_enabled recording.stereo notifications.enabled transcriber.enabled transcriber.output_dir transcriber.language transcriber.output_format transcriber.whisper_path transcriber.model_path transcriber.tinydiarize_model_path transcriber.whisper_manifest_url transcriber.whisper_url transcriber.model_url transcriber.tinydiarize_model_url; do
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

is_transcriber_running() {
    [ -f "${transcriber_pid_file}" ] || return 1

    pid=$(cat "${transcriber_pid_file}" 2>/dev/null || true)
    [ -n "${pid}" ] || return 1

    kill -0 "${pid}" 2>/dev/null
}

clear_stale_transcriber_pid() {
    if ! is_transcriber_running; then
        rm -f "${transcriber_pid_file}"
    fi
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
    stereo_enabled=$(bool_string config_is_enabled recording.stereo 0)

    : > "${daemon_log}"

    # Run from the module directory instead of installing anything into /system
    # or PackageManager. The daemon's code lives in the helper APK under tools/.
    CLASSPATH="${helper_apk}" app_process / \
        com.chiller3.bcr.headless.HeadlessMain \
        daemon "${mod_dir}" "${output_dir}" "${min_duration}" "${log_enabled}" "${notifications_enabled}" "${stereo_enabled}" \
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

start_transcriber_worker() {
    ensure_dirs
    ensure_defaults
    clear_stale_transcriber_pid

    if ! config_is_enabled transcriber.enabled 0; then
        return 0
    fi

    if is_transcriber_running; then
        return 0
    fi

    if [ ! -f "${helper_apk}" ]; then
        echo "Missing helper APK: ${helper_apk}" >&2
        return 1
    fi

    whisper_path=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
    model_path=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
    tdrz_model_path=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")
    notifications_enabled=$(bool_string config_is_enabled notifications.enabled 1)

    rm -f "${transcriber_stop_file}"
    : > "${transcriber_log}"

    CLASSPATH="${helper_apk}" app_process / \
        com.chiller3.bcr.headless.HeadlessMain \
        transcriber worker "${mod_dir}" "${whisper_path}" "${model_path}" "${tdrz_model_path}" "${notifications_enabled}" \
        >>"${transcriber_log}" 2>&1 &
    echo "${!}" > "${transcriber_pid_file}"

    sleep 1
    clear_stale_transcriber_pid
}

component_status_set() {
    key="${1}"
    value="${2}"

    ensure_dirs
    touch "${transcriber_components_file}"
    tmp="${transcriber_components_file}.tmp"
    awk -F= -v target="${key}" '$1 != target { print }' "${transcriber_components_file}" > "${tmp}" 2>/dev/null || true
    printf '%s=%s\n' "${key}" "${value}" >> "${tmp}"
    mv "${tmp}" "${transcriber_components_file}"
}

detect_android_abi() {
    abi_list=$(getprop ro.product.cpu.abilist 2>/dev/null || true)
    if [ -z "${abi_list}" ]; then
        abi_list=$(getprop ro.product.cpu.abi 2>/dev/null || true)
    fi

    case ",${abi_list}," in
        *",arm64-v8a,"*)
            printf 'arm64-v8a'
            ;;
        *",armeabi-v7a,"*)
            printf 'armeabi-v7a'
            ;;
        *",x86_64,"*)
            printf 'x86_64'
            ;;
        *)
            first_abi=$(printf '%s' "${abi_list}" | cut -d, -f1)
            printf '%s' "${first_abi:-unknown}"
            ;;
    esac
}

manifest_get() {
    key="${1}"
    file="${2}"

    awk -F= -v target="${key}" '$1 == target { print substr($0, length($1) + 2); exit }' "${file}" 2>/dev/null
}

fetch_url_to_file() {
    url="${1}"
    dest="${2}"

    if command -v curl >/dev/null 2>&1; then
        curl -L --fail -o "${dest}" "${url}" >/dev/null 2>&1
    elif command -v wget >/dev/null 2>&1; then
        wget -O "${dest}" "${url}" >/dev/null 2>&1
    else
        return 2
    fi
}

sha256_of_file() {
    path="${1}"

    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "${path}" 2>/dev/null | awk '{ print $1 }'
        return 0
    fi
    if command -v toybox >/dev/null 2>&1; then
        toybox sha256sum "${path}" 2>/dev/null | awk '{ print $1 }'
        return 0
    fi
    if command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "${path}" 2>/dev/null | awk '{ print $NF }'
        return 0
    fi

    return 1
}

verify_sha256() {
    path="${1}"
    expected="${2}"

    [ -n "${expected}" ] || return 0

    actual=$(sha256_of_file "${path}" 2>/dev/null || true)
    if [ -z "${actual}" ]; then
        return 2
    fi

    [ "$(printf '%s' "${actual}" | tr '[:upper:]' '[:lower:]')" = "$(printf '%s' "${expected}" | tr '[:upper:]' '[:lower:]')" ]
}

resolve_whisper_cli_component() {
    resolved_whisper_abi=$(detect_android_abi)
    resolved_whisper_url=$(config_get_or_default transcriber.whisper_url "${default_whisper_url}")
    resolved_whisper_sha256=""
    resolved_whisper_size="${default_whisper_size}"
    resolved_whisper_manifest_url=$(config_get_or_default transcriber.whisper_manifest_url "${default_whisper_manifest_url}")

    component_status_set component.whisper_cli.abi "${resolved_whisper_abi}"
    component_status_set component.whisper_cli.manifest_url "${resolved_whisper_manifest_url}"

    if [ -n "${resolved_whisper_url}" ]; then
        component_status_set component.whisper_cli.url "${resolved_whisper_url}"
        component_status_set component.whisper_cli.sha256 ""
        return 0
    fi

    if [ "${resolved_whisper_abi}" = "unknown" ]; then
        component_status_set component.whisper_cli.status failed
        component_status_set component.whisper_cli.error "Unable to detect Android CPU ABI"
        return 1
    fi

    manifest_path="${state_dir}/transcriber-tools.env"
    manifest_tmp="${manifest_path}.download"
    component_status_set component.whisper_cli.status resolving
    component_status_set component.whisper_cli.error ""

    if ! fetch_url_to_file "${resolved_whisper_manifest_url}" "${manifest_tmp}"; then
        rm -f "${manifest_tmp}"
        component_status_set component.whisper_cli.status failed
        component_status_set component.whisper_cli.error "Unable to download transcriber tools manifest"
        return 1
    fi
    mv "${manifest_tmp}" "${manifest_path}"

    manifest_ref=$(manifest_get whisper_cpp_ref "${manifest_path}")
    manifest_commit=$(manifest_get whisper_cpp_commit "${manifest_path}")
    manifest_build=$(manifest_get build "${manifest_path}")
    resolved_whisper_url=$(manifest_get "abi.${resolved_whisper_abi}.url" "${manifest_path}")
    resolved_whisper_sha256=$(manifest_get "abi.${resolved_whisper_abi}.sha256" "${manifest_path}")
    resolved_whisper_size=$(manifest_get "abi.${resolved_whisper_abi}.size" "${manifest_path}")
    [ -n "${resolved_whisper_size}" ] || resolved_whisper_size="${default_whisper_size}"

    component_status_set component.whisper_cli.url "${resolved_whisper_url}"
    component_status_set component.whisper_cli.sha256 "${resolved_whisper_sha256}"
    component_status_set component.whisper_cli.whisper_ref "${manifest_ref}"
    component_status_set component.whisper_cli.whisper_commit "${manifest_commit}"
    component_status_set component.whisper_cli.build "${manifest_build}"
    component_status_set component.whisper_cli.bytes_total "${resolved_whisper_size}"

    if [ -z "${resolved_whisper_url}" ]; then
        component_status_set component.whisper_cli.status failed
        component_status_set component.whisper_cli.error "No whisper-cli package for ABI ${resolved_whisper_abi}"
        return 1
    fi

    return 0
}

component_status_reset() {
    whisper_path=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
    model_path=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
    tdrz_model_path=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")
    whisper_url=$(config_get_or_default transcriber.whisper_url "${default_whisper_url}")
    whisper_manifest_url=$(config_get_or_default transcriber.whisper_manifest_url "${default_whisper_manifest_url}")
    whisper_abi=$(detect_android_abi)
    model_url=$(config_get_or_default transcriber.model_url "${default_model_url}")
    tdrz_model_url=$(config_get_or_default transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}")
    total_estimated=$((default_whisper_size + default_model_size + default_tinydiarize_model_size))

    cat > "${transcriber_components_file}" <<EOF
transcriber.components.state=idle
transcriber.components.running=0
transcriber.components.error=
transcriber.components.total_estimated_bytes=${total_estimated}
component.whisper_cli.label=whisper.cpp CLI package
component.whisper_cli.status=missing
component.whisper_cli.progress=0
component.whisper_cli.bytes_downloaded=0
component.whisper_cli.bytes_total=${default_whisper_size}
component.whisper_cli.abi=${whisper_abi}
component.whisper_cli.manifest_url=${whisper_manifest_url}
component.whisper_cli.path=${whisper_path}
component.whisper_cli.url=${whisper_url}
component.whisper_cli.sha256=
component.base_model.label=Whisper base English model
component.base_model.status=missing
component.base_model.progress=0
component.base_model.bytes_downloaded=0
component.base_model.bytes_total=${default_model_size}
component.base_model.path=${model_path}
component.base_model.url=${model_url}
component.tinydiarize_model.label=TinyDiarize speaker model
component.tinydiarize_model.status=missing
component.tinydiarize_model.progress=0
component.tinydiarize_model.bytes_downloaded=0
component.tinydiarize_model.bytes_total=${default_tinydiarize_model_size}
component.tinydiarize_model.path=${tdrz_model_path}
component.tinydiarize_model.url=${tdrz_model_url}
EOF

    if [ -f "${whisper_path}" ]; then
        if verify_whisper_cli_executable "${whisper_path}"; then
            component_status_set component.whisper_cli.status ready
            component_status_set component.whisper_cli.progress 100
            component_status_set component.whisper_cli.bytes_downloaded "$(file_size_bytes "${whisper_path}")"
        else
            component_status_set component.whisper_cli.status failed
            component_status_set component.whisper_cli.error "Existing whisper-cli could not execute on this device"
        fi
    fi
    if [ -f "${model_path}" ]; then
        component_status_set component.base_model.status ready
        component_status_set component.base_model.progress 100
        component_status_set component.base_model.bytes_downloaded "$(file_size_bytes "${model_path}")"
    fi
    if [ -f "${tdrz_model_path}" ]; then
        component_status_set component.tinydiarize_model.status ready
        component_status_set component.tinydiarize_model.progress 100
        component_status_set component.tinydiarize_model.bytes_downloaded "$(file_size_bytes "${tdrz_model_path}")"
    fi
}

print_transcriber_components_status() {
    ensure_dirs
    ensure_defaults

    if [ ! -f "${transcriber_components_file}" ]; then
        component_status_reset
    fi

    if [ -f "${transcriber_components_pid_file}" ]; then
        pid=$(cat "${transcriber_components_pid_file}" 2>/dev/null || true)
        if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
            components_running=1
        else
            rm -f "${transcriber_components_pid_file}"
            components_running=0
        fi
    else
        components_running=0
    fi

    cat "${transcriber_components_file}"
    echo "transcriber.components.running=${components_running}"
}

file_size_bytes() {
    path="${1}"
    if [ ! -f "${path}" ]; then
        printf '0'
        return
    fi

    if command -v stat >/dev/null 2>&1; then
        stat -c '%s' "${path}" 2>/dev/null && return
    fi

    wc -c < "${path}" 2>/dev/null | tr -d ' '
}

is_zip_file() {
    path="${1}"
    magic=$(dd if="${path}" bs=2 count=1 2>/dev/null)
    [ "${magic}" = "PK" ]
}

extract_whisper_cli() {
    package="${1}"
    dest="${2}"
    extracted="${dest}.extract"

    if ! command -v unzip >/dev/null 2>&1; then
        return 1
    fi

    rm -f "${extracted}"
    unzip -p "${package}" "whisper-cli" > "${extracted}" 2>/dev/null ||
        unzip -p "${package}" "*/whisper-cli" > "${extracted}" 2>/dev/null ||
        unzip -p "${package}" "whisper-cli.exe" > "${extracted}" 2>/dev/null ||
        unzip -p "${package}" "*/whisper-cli.exe" > "${extracted}" 2>/dev/null

    if [ -s "${extracted}" ]; then
        mv "${extracted}" "${dest}"
        chmod 755 "${dest}" 2>/dev/null || true
        return 0
    fi

    rm -f "${extracted}"
    return 1
}

verify_whisper_cli_executable() {
    dest="${1}"

    chmod 755 "${dest}" 2>/dev/null || true
    if [ ! -x "${dest}" ]; then
        return 1
    fi

    "${dest}" -h >/dev/null 2>&1 || "${dest}" --help >/dev/null 2>&1
}

download_component() {
    component="${1}"
    url="${2}"
    dest="${3}"
    estimated_size="${4}"
    extract_mode="${5}"
    expected_sha256="${6:-}"
    tmp="${dest}.download"

    component_status_set "component.${component}.path" "${dest}"
    component_status_set "component.${component}.url" "${url}"
    component_status_set "component.${component}.bytes_total" "${estimated_size}"
    component_status_set "component.${component}.sha256" "${expected_sha256}"

    if [ -f "${dest}" ]; then
        if [ "${component}" = "whisper_cli" ] && ! verify_whisper_cli_executable "${dest}"; then
            component_status_set "component.${component}.status" failed
            component_status_set "component.${component}.error" "Existing whisper-cli could not execute on this device"
            return 1
        fi
        component_status_set "component.${component}.status" ready
        component_status_set "component.${component}.progress" 100
        component_status_set "component.${component}.bytes_downloaded" "$(file_size_bytes "${dest}")"
        [ "${component}" = "whisper_cli" ] && chmod 755 "${dest}" 2>/dev/null || true
        return 0
    fi

    if [ -z "${url}" ]; then
        component_status_set "component.${component}.status" failed
        component_status_set "component.${component}.error" "No download URL configured"
        return 1
    fi

    mkdir -p "$(dirname "${dest}")"
    rm -f "${tmp}"

    component_status_set "component.${component}.status" downloading
    component_status_set "component.${component}.progress" 0
    component_status_set "component.${component}.bytes_downloaded" 0
    component_status_set "component.${component}.error" ""

    if command -v curl >/dev/null 2>&1; then
        curl -L --fail -o "${tmp}" "${url}" >/dev/null 2>&1 &
        download_pid="${!}"
    elif command -v wget >/dev/null 2>&1; then
        wget -O "${tmp}" "${url}" >/dev/null 2>&1 &
        download_pid="${!}"
    else
        component_status_set "component.${component}.status" failed
        component_status_set "component.${component}.error" "No curl or wget found"
        return 1
    fi

    while kill -0 "${download_pid}" 2>/dev/null; do
        downloaded=$(file_size_bytes "${tmp}")
        if [ "${estimated_size}" -gt 0 ]; then
            progress=$((downloaded * 100 / estimated_size))
            [ "${progress}" -gt 99 ] && progress=99
        else
            progress=0
        fi
        component_status_set "component.${component}.bytes_downloaded" "${downloaded}"
        component_status_set "component.${component}.progress" "${progress}"
        sleep 1
    done

    wait "${download_pid}"
    result=$?
    downloaded=$(file_size_bytes "${tmp}")
    component_status_set "component.${component}.bytes_downloaded" "${downloaded}"

    if [ "${result}" -ne 0 ] || [ ! -s "${tmp}" ]; then
        rm -f "${tmp}"
        component_status_set "component.${component}.status" failed
        component_status_set "component.${component}.progress" 0
        component_status_set "component.${component}.error" "Download failed"
        return 1
    fi

    if [ -n "${expected_sha256}" ]; then
        component_status_set "component.${component}.status" verifying
        if ! verify_sha256 "${tmp}" "${expected_sha256}"; then
            rm -f "${tmp}"
            component_status_set "component.${component}.status" failed
            component_status_set "component.${component}.error" "SHA-256 verification failed"
            return 1
        fi
    fi

    if [ "${extract_mode}" = "whisper_cli" ] && is_zip_file "${tmp}"; then
        if ! extract_whisper_cli "${tmp}" "${dest}"; then
            rm -f "${tmp}"
            component_status_set "component.${component}.status" failed
            component_status_set "component.${component}.error" "Downloaded package did not contain whisper-cli"
            return 1
        fi
        rm -f "${tmp}"
    else
        mv "${tmp}" "${dest}"
        [ "${component}" = "whisper_cli" ] && chmod 755 "${dest}" 2>/dev/null || true
    fi

    if [ "${component}" = "whisper_cli" ] && ! verify_whisper_cli_executable "${dest}"; then
        rm -f "${dest}"
        component_status_set "component.${component}.status" failed
        component_status_set "component.${component}.error" "Downloaded whisper-cli could not execute on this device"
        return 1
    fi

    component_status_set "component.${component}.status" ready
    component_status_set "component.${component}.progress" 100
    component_status_set "component.${component}.bytes_downloaded" "$(file_size_bytes "${dest}")"
    component_status_set "component.${component}.error" ""
    return 0
}

install_transcriber_dependencies_foreground() {
    ensure_dirs
    ensure_defaults
    component_status_reset
    component_status_set transcriber.components.state running
    component_status_set transcriber.components.error ""

    whisper_path=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
    model_path=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
    tdrz_model_path=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")
    model_url=$(config_get_or_default transcriber.model_url "${default_model_url}")
    tdrz_model_url=$(config_get_or_default transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}")

    failed=0
    failed_names=""

    if resolve_whisper_cli_component; then
        if ! download_component whisper_cli "${resolved_whisper_url}" "${whisper_path}" "${resolved_whisper_size}" whisper_cli "${resolved_whisper_sha256}"; then
            failed=1
            failed_names="${failed_names} whisper.cpp-cli"
        fi
    else
        failed=1
        failed_names="${failed_names} whisper.cpp-cli"
    fi
    if ! download_component base_model "${model_url}" "${model_path}" "${default_model_size}" direct ""; then
        failed=1
        failed_names="${failed_names} base-model"
    fi
    if ! download_component tinydiarize_model "${tdrz_model_url}" "${tdrz_model_path}" "${default_tinydiarize_model_size}" direct ""; then
        failed=1
        failed_names="${failed_names} tinydiarize-model"
    fi

    if [ "${failed}" -ne 0 ]; then
        component_status_set transcriber.components.state failed
        component_status_set transcriber.components.error "Failed:${failed_names}"
        return 1
    else
        component_status_set transcriber.components.state ready
        component_status_set transcriber.components.error ""
    fi
}

install_transcriber_dependencies() {
    ensure_dirs
    ensure_defaults

    if [ -f "${transcriber_components_pid_file}" ]; then
        pid=$(cat "${transcriber_components_pid_file}" 2>/dev/null || true)
        if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
            echo "transcriber.components.running=1"
            return 0
        fi
    fi

    component_status_reset
    component_status_set transcriber.components.state running
    component_status_set transcriber.components.running 1
    ( install_transcriber_dependencies_foreground ) >>"${transcriber_log}" 2>&1 &
    echo "${!}" > "${transcriber_components_pid_file}"
    echo "transcriber.components.started=1"
    print_transcriber_components_status
}

remove_transcriber_dependencies() {
    rm -rf "${transcriber_tools_dir}"
    rm -f "${transcriber_pid_file}" "${transcriber_runtime_file}" "${transcriber_stop_file}" "${transcriber_pause_file}" "${transcriber_components_file}" "${transcriber_components_pid_file}"
    rm -rf "${state_dir}/transcriber-work"
}

print_status() {
    ensure_dirs
    clear_stale_pid
    status_output_dir=$(config_get_or_default output.dir /sdcard/Recordings/BCR)
    status_transcriber_output_dir=$(config_get_or_default transcriber.output_dir "${status_output_dir}/transcripts")
    status_whisper_path=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
    status_model_path=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
    status_tdrz_model_path=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")

    echo "module.id=${module_id}"
    echo "module.path=${mod_dir}"
    echo "recording.enabled=$(bool_string config_is_enabled recording.enabled 0)"
    echo "output.dir=${status_output_dir}"
    echo "recording.min_duration=$(config_get_or_default recording.min_duration 0)"
    echo "recording.log_enabled=$(bool_string config_is_enabled recording.log_enabled 1)"
    echo "recording.stereo=$(bool_string config_is_enabled recording.stereo 0)"
    echo "notifications.enabled=$(bool_string config_is_enabled notifications.enabled 1)"
    echo "transcriber.enabled=$(bool_string config_is_enabled transcriber.enabled 0)"
    echo "transcriber.output_dir=${status_transcriber_output_dir}"
    echo "transcriber.language=$(config_get_or_default transcriber.language en)"
    echo "transcriber.output_format=$(config_get_or_default transcriber.output_format txt)"
    echo "transcriber.whisper_path=${status_whisper_path}"
    echo "transcriber.model_path=${status_model_path}"
    echo "transcriber.tinydiarize_model_path=${status_tdrz_model_path}"
    echo "transcriber.device_abi=$(detect_android_abi)"
    echo "transcriber.whisper_manifest_url=$(config_get_or_default transcriber.whisper_manifest_url "${default_whisper_manifest_url}")"
    echo "transcriber.whisper_url=$(config_get_or_default transcriber.whisper_url "${default_whisper_url}")"
    echo "transcriber.model_url=$(config_get_or_default transcriber.model_url "${default_model_url}")"
    echo "transcriber.tinydiarize_model_url=$(config_get_or_default transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}")"
    echo "transcriber.components.estimated_bytes=$((default_whisper_size + default_model_size + default_tinydiarize_model_size))"
    echo "daemon.running=$(bool_string is_daemon_running)"
    echo "daemon.pid=$(cat "${pid_file}" 2>/dev/null || true)"
    clear_stale_transcriber_pid
    echo "transcriber.running=$(bool_string is_transcriber_running)"
    echo "transcriber.pid=$(cat "${transcriber_pid_file}" 2>/dev/null || true)"

    if [ -f "${runtime_file}" ]; then
        cat "${runtime_file}"
    fi
}

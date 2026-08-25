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
transcriber_manifest_cache="${state_dir}/transcriber-tools.env"
transcriber_tools_dir="${mod_dir}/tools/transcriber"
default_output_dir="/sdcard/Recordings/BCRHeadless"
legacy_default_output_dir="/sdcard/Recordings/BCR"
default_transcript_subdir="transcripts"
default_transcriber_tools_release_base_url="https://github.com/wjdob/BCR-Headless/releases/download/transcriber-tools"
default_whisper_manifest_url="${default_transcriber_tools_release_base_url}/transcriber-tools.env"
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

    # The local mirror is authoritative during normal module operation. This
    # keeps WebUI snapshots from spawning one ksud process per setting.
    if [ -f "${config_dir}/${key}" ]; then
        cat "${config_dir}/${key}"
        return 0
    fi

    if has_ksud; then
        value=$(ksud module config get "${key}" 2>/dev/null || true)
        if [ -n "${value}" ]; then
            ensure_dirs
            printf '%s' "${value}" > "${config_dir}/${key}"
            printf '%s' "${value}"
            return 0
        fi
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
    keys="recording.enabled output.dir recording.min_duration recording.log_enabled recording.stereo recording.format recording.available_formats recording.detected_mode recording.voice_call_stereo_supported recording.voice_call_probe_status recording.voice_call_probe_note notifications.enabled debug.enabled transcriber.enabled transcriber.output_dir transcriber.language transcriber.output_format transcriber.speaker_self_name transcriber.speaker_remote_name transcriber.whisper_path transcriber.model_path transcriber.tinydiarize_model_path transcriber.whisper_manifest_url transcriber.model_url transcriber.tinydiarize_model_url override.description"

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

default_transcript_dir_for() {
    transcript_output_dir_base="${1:-$(config_get_or_default output.dir "${default_output_dir}")}"
    printf '%s/%s' "${transcript_output_dir_base}" "${default_transcript_subdir}"
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

status_value_from_blob() {
    key="${1}"
    blob="${2}"

    printf '%s\n' "${blob}" | awk -F= -v target="${key}" '$1 == target { print substr($0, length($1) + 2); exit }'
}

cache_recording_capability_probe() {
    if [ ! -f "${helper_apk}" ]; then
        config_set recording.detected_mode stereo
        config_set recording.voice_call_stereo_supported 1
        config_set recording.voice_call_probe_status helper_missing
        config_set recording.voice_call_probe_note "Helper APK missing; assuming stereo until probed later"
        config_set recording.available_formats wav
        return
    fi

    probe_output=$(run_helper_foreground recording-capability 2>/dev/null || true)
    available_formats=$(status_value_from_blob recording.available_formats "${probe_output}")
    detected_mode=$(status_value_from_blob recording.detected_mode "${probe_output}")
    stereo_supported=$(status_value_from_blob recording.voice_call_stereo_supported "${probe_output}")
    probe_status=$(status_value_from_blob recording.voice_call_probe_status "${probe_output}")
    probe_note=$(status_value_from_blob recording.voice_call_probe_note "${probe_output}")

    case "${available_formats}" in
        *wav*)
            config_set recording.available_formats "${available_formats}"
            ;;
        *)
            config_set recording.available_formats wav
            ;;
    esac

    case "${detected_mode}" in
        stereo|mono)
            config_set recording.detected_mode "${detected_mode}"
            ;;
        *)
            config_set recording.detected_mode stereo
            ;;
    esac

    case "${stereo_supported}" in
        0|1)
            config_set recording.voice_call_stereo_supported "${stereo_supported}"
            ;;
        *)
            config_set recording.voice_call_stereo_supported 1
            ;;
    esac

    config_set recording.voice_call_probe_status "${probe_status:-assumed_stereo}"
    config_set recording.voice_call_probe_note "${probe_note:-Stereo VOICE_CALL check unavailable; assuming stereo until overridden}"
}

ensure_defaults() {
    # Pick conservative defaults that keep the module inert until the user opts
    # in from the WebUI. That avoids starting call-state monitoring immediately
    # after flashing the module.
    if ! config_get recording.enabled >/dev/null 2>&1; then
        config_set recording.enabled 0
    fi
    if ! config_get output.dir >/dev/null 2>&1; then
        config_set output.dir "${default_output_dir}"
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
    if ! config_get recording.detected_mode >/dev/null 2>&1 || ! config_get recording.voice_call_stereo_supported >/dev/null 2>&1 || ! config_get recording.voice_call_probe_note >/dev/null 2>&1; then
        cache_recording_capability_probe
    fi
    if ! config_get recording.stereo >/dev/null 2>&1; then
        detected_mode=$(config_get_or_default recording.detected_mode stereo)
        if [ "${detected_mode}" = "mono" ]; then
            config_set recording.stereo 0
        else
            config_set recording.stereo 1
        fi
    fi
    if ! config_get recording.format >/dev/null 2>&1; then
        config_set recording.format wav
    fi
    if ! config_get recording.available_formats >/dev/null 2>&1; then
        cache_recording_capability_probe
    fi
    current_recording_format=$(config_get_or_default recording.format wav)
    available_recording_formats=$(config_get_or_default recording.available_formats wav)
    case ",${available_recording_formats}," in
        *",${current_recording_format},"*)
            ;;
        *)
            config_set recording.format wav
            ;;
    esac
    if ! config_get debug.enabled >/dev/null 2>&1; then
        config_set debug.enabled 0
    fi
    if ! config_get transcriber.enabled >/dev/null 2>&1; then
        config_set transcriber.enabled 0
    fi
    if ! config_get transcriber.output_dir >/dev/null 2>&1; then
        config_set transcriber.output_dir "$(default_transcript_dir_for)"
    fi
    if ! config_get transcriber.language >/dev/null 2>&1; then
        config_set transcriber.language en
    fi
    if ! config_get transcriber.output_format >/dev/null 2>&1; then
        config_set transcriber.output_format txt
    fi
    if ! config_get transcriber.speaker_self_name >/dev/null 2>&1; then
        config_set transcriber.speaker_self_name "Speaker A"
    fi
    if ! config_get transcriber.speaker_remote_name >/dev/null 2>&1; then
        config_set transcriber.speaker_remote_name "Speaker B"
    fi
    # Auto-queue and charging-gated transcription were experimental and have
    # been removed. Clear any leftover config from older builds.
    if config_get transcriber.auto_queue >/dev/null 2>&1; then
        config_delete transcriber.auto_queue
    fi
    if config_get transcriber.auto_queue_require_charging >/dev/null 2>&1; then
        config_delete transcriber.auto_queue_require_charging
    fi
    if config_get transcriber.auto_queue_charge_delay_seconds >/dev/null 2>&1; then
        config_delete transcriber.auto_queue_charge_delay_seconds
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
    # Component sources are curated by the module. Remove obsolete local and
    # arbitrary URL overrides left by earlier experimental builds.
    if config_get transcriber.whisper_url >/dev/null 2>&1; then
        config_delete transcriber.whisper_url
    fi
    if config_get transcriber.whisper_local_path >/dev/null 2>&1; then
        config_delete transcriber.whisper_local_path
    fi
    if ! config_get transcriber.whisper_manifest_url >/dev/null 2>&1; then
        config_set transcriber.whisper_manifest_url "${default_whisper_manifest_url}"
    fi
    current_whisper_manifest_url=$(config_get_or_default transcriber.whisper_manifest_url "")
    if [ "${current_whisper_manifest_url}" != "${default_whisper_manifest_url}" ]; then
        config_set transcriber.whisper_manifest_url "${default_whisper_manifest_url}"
    fi
    if ! config_get transcriber.model_url >/dev/null 2>&1; then
        config_set transcriber.model_url "${default_model_url}"
    fi
    current_model_url=$(config_get_or_default transcriber.model_url "")
    case "${current_model_url}" in
        https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin|\
        https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin|\
        https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin|\
        https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin)
            ;;
        *)
            config_set transcriber.model_url "${default_model_url}"
            ;;
    esac
    if ! config_get transcriber.tinydiarize_model_url >/dev/null 2>&1; then
        config_set transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}"
    fi
    current_tdrz_url=$(config_get_or_default transcriber.tinydiarize_model_url "")
    if [ "${current_tdrz_url}" != "${default_tinydiarize_model_url}" ]; then
        config_set transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}"
    fi
}

reset_defaults() {
    # Drop the explicit config files and reapply the documented defaults so the
    # WebUI and the daemon always converge back to the same baseline values.
    for key in recording.enabled output.dir recording.min_duration recording.log_enabled recording.stereo recording.format recording.available_formats recording.detected_mode recording.voice_call_stereo_supported recording.voice_call_probe_status recording.voice_call_probe_note notifications.enabled debug.enabled transcriber.enabled transcriber.output_dir transcriber.language transcriber.output_format transcriber.speaker_self_name transcriber.speaker_remote_name transcriber.whisper_path transcriber.model_path transcriber.tinydiarize_model_path transcriber.whisper_manifest_url transcriber.whisper_url transcriber.whisper_local_path transcriber.model_url transcriber.tinydiarize_model_url; do
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
    output_dir=$(config_get_or_default output.dir "${default_output_dir}")
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

    output_dir=$(config_get_or_default output.dir "${default_output_dir}")
    min_duration=$(config_get_or_default recording.min_duration 0)
    log_enabled=$(bool_string config_is_enabled recording.log_enabled 1)
    notifications_enabled=$(bool_string config_is_enabled notifications.enabled 1)
    stereo_enabled=$(bool_string config_is_enabled recording.stereo 1)
    recording_format=$(config_get_or_default recording.format wav)

    touch "${daemon_log}"
    printf '\n[%s] daemon start\n' "$(component_timestamp)" >> "${daemon_log}"

    # Run from the module directory instead of installing anything into /system
    # or PackageManager. The daemon's code lives in the helper APK under tools/.
    CLASSPATH="${helper_apk}" app_process / \
        com.chiller3.bcr.headless.HeadlessMain \
        daemon "${mod_dir}" "${output_dir}" "${min_duration}" "${log_enabled}" "${notifications_enabled}" "${stereo_enabled}" "${recording_format}" \
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
    speaker_self_name=$(config_get_or_default transcriber.speaker_self_name "Speaker A")

    rm -f "${transcriber_stop_file}"
    touch "${transcriber_log}"
    printf '\n[%s] transcriber worker start\n' "$(component_timestamp)" >> "${transcriber_log}"

    CLASSPATH="${helper_apk}" app_process / \
        com.chiller3.bcr.headless.HeadlessMain \
        transcriber worker "${mod_dir}" "${whisper_path}" "${model_path}" "${tdrz_model_path}" "${notifications_enabled}" "${speaker_self_name}" \
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

component_timestamp() {
    date '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || date 2>/dev/null || printf 'unknown'
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

component_status_reset() {
    whisper_path=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
    model_path=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
    tdrz_model_path=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")
    whisper_manifest_url=$(config_get_or_default transcriber.whisper_manifest_url "${default_whisper_manifest_url}")
    whisper_abi=$(detect_android_abi)
    model_url=$(config_get_or_default transcriber.model_url "${default_model_url}")
    tdrz_model_url=$(config_get_or_default transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}")
    whisper_source_kind=manifest
    whisper_source_detail="${whisper_manifest_url}"
    whisper_bytes_total="${default_whisper_size}"
    prepare_profile=stereo
    base_model_initial_status=missing
    base_model_note=""
    tdrz_model_initial_status=missing
    tdrz_model_note=""

    if ! config_is_enabled recording.stereo 1; then
        prepare_profile=mono
        base_model_initial_status=optional
        base_model_note="Not selected for mono fallback preparation"
    else
        tdrz_model_initial_status=optional
        tdrz_model_note="Not selected for stereo preparation"
    fi

    if [ "${prepare_profile}" = mono ]; then
        total_estimated=$((whisper_bytes_total + default_tinydiarize_model_size))
    else
        total_estimated=$((whisper_bytes_total + default_model_size))
    fi

    cat > "${transcriber_components_file}" <<EOF
transcriber.components.state=idle
transcriber.components.running=0
transcriber.components.error=
transcriber.components.log=${transcriber_log}
transcriber.components.started_at=
transcriber.components.completed_at=
transcriber.components.profile=${prepare_profile}
transcriber.components.total_estimated_bytes=${total_estimated}
component.whisper_cli.label=whisper.cpp CLI package
component.whisper_cli.status=missing
component.whisper_cli.progress=0
component.whisper_cli.bytes_downloaded=0
component.whisper_cli.bytes_total=${whisper_bytes_total}
component.whisper_cli.bytes_per_second=0
component.whisper_cli.abi=${whisper_abi}
component.whisper_cli.manifest_url=${whisper_manifest_url}
component.whisper_cli.path=${whisper_path}
component.whisper_cli.url=
component.whisper_cli.source_kind=${whisper_source_kind}
component.whisper_cli.source_detail=${whisper_source_detail}
component.whisper_cli.sha256=
component.whisper_cli.error=
component.base_model.label=Whisper model
component.base_model.status=${base_model_initial_status}
component.base_model.progress=0
component.base_model.bytes_downloaded=0
component.base_model.bytes_total=${default_model_size}
component.base_model.bytes_per_second=0
component.base_model.path=${model_path}
component.base_model.url=${model_url}
component.base_model.note=${base_model_note}
component.tinydiarize_model.label=TinyDiarize model
component.tinydiarize_model.status=${tdrz_model_initial_status}
component.tinydiarize_model.progress=0
component.tinydiarize_model.bytes_downloaded=0
component.tinydiarize_model.bytes_total=${default_tinydiarize_model_size}
component.tinydiarize_model.bytes_per_second=0
component.tinydiarize_model.path=${tdrz_model_path}
component.tinydiarize_model.url=${tdrz_model_url}
component.tinydiarize_model.note=${tdrz_model_note}
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
        component_status_set component.base_model.note ""
    fi
    if [ -f "${tdrz_model_path}" ]; then
        component_status_set component.tinydiarize_model.status ready
        component_status_set component.tinydiarize_model.progress 100
        component_status_set component.tinydiarize_model.bytes_downloaded "$(file_size_bytes "${tdrz_model_path}")"
        component_status_set component.tinydiarize_model.note ""
    fi
}

print_transcriber_components_status() {
    ensure_dirs
    ensure_defaults

    if [ ! -f "${transcriber_components_file}" ] ||
        ! grep -q '^component\.whisper_cli\.source_kind=' "${transcriber_components_file}" 2>/dev/null; then
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

refresh_transcriber_components_metadata() {
    ensure_dirs
    ensure_defaults

    if [ -f "${transcriber_components_pid_file}" ]; then
        pid=$(cat "${transcriber_components_pid_file}" 2>/dev/null || true)
        if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
            print_transcriber_components_status
            return 0
        fi
    fi

    whisper_path=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
    whisper_manifest_url=$(config_get_or_default transcriber.whisper_manifest_url "${default_whisper_manifest_url}")
    model_path=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
    model_url=$(config_get_or_default transcriber.model_url "${default_model_url}")
    tdrz_model_path=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")
    tdrz_model_url=$(config_get_or_default transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}")

    run_helper_foreground \
        transcriber refresh-component-metadata \
        "${mod_dir}" \
        "${whisper_path}" \
        "${whisper_manifest_url}" \
        "${model_path}" \
        "${model_url}" \
        "${tdrz_model_path}" \
        "${tdrz_model_url}" >/dev/null

    print_transcriber_components_status
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

verify_whisper_cli_executable() {
    dest="${1}"

    chmod 755 "${dest}" 2>/dev/null || true
    if [ ! -x "${dest}" ]; then
        return 1
    fi

    help_output=$("${dest}" -h 2>&1)
    help_result=$?
    if [ "${help_result}" -eq 0 ] || printf '%s' "${help_output}" | grep -Eiq 'usage|whisper|options'; then
        return 0
    fi

    help_output=$("${dest}" --help 2>&1)
    help_result=$?
    [ "${help_result}" -eq 0 ] || printf '%s' "${help_output}" | grep -Eiq 'usage|whisper|options'
}

install_transcriber_dependencies_foreground() {
    ensure_dirs
    ensure_defaults
    prepare_profile="${1:-stereo}"
    component_status_reset
    component_status_set transcriber.components.profile "${prepare_profile}"
    whisper_path=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
    whisper_manifest_url=$(config_get_or_default transcriber.whisper_manifest_url "${default_whisper_manifest_url}")
    model_path=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
    model_url=$(config_get_or_default transcriber.model_url "${default_model_url}")
    tdrz_model_path=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")
    tdrz_model_url=$(config_get_or_default transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}")

    if ! run_helper_foreground \
        transcriber prepare-components \
        "${mod_dir}" \
        "${whisper_path}" \
        "${whisper_manifest_url}" \
        "${model_path}" \
        "${model_url}" \
        "${tdrz_model_path}" \
        "${tdrz_model_url}" \
        "${prepare_profile}"; then
        component_status_set transcriber.components.state failed
        if [ -z "$(grep '^transcriber.components.error=' "${transcriber_components_file}" 2>/dev/null || true)" ]; then
            component_status_set transcriber.components.error "Failed: helper prepare-components crashed"
        fi
        component_status_set transcriber.components.running 0
        component_status_set transcriber.components.completed_at "$(component_timestamp)"
        return 1
    fi
}

install_transcriber_dependencies() {
    ensure_dirs
    ensure_defaults
    prepare_profile="${1:-stereo}"

    if [ -f "${transcriber_components_pid_file}" ]; then
        pid=$(cat "${transcriber_components_pid_file}" 2>/dev/null || true)
        if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
            echo "transcriber.components.running=1"
            return 0
        fi
    fi

    component_status_reset
    component_status_set transcriber.components.profile "${prepare_profile}"
    component_status_set transcriber.components.state running
    component_status_set transcriber.components.running 1
    ( install_transcriber_dependencies_foreground "${prepare_profile}" ) >>"${transcriber_log}" 2>&1 &
    echo "${!}" > "${transcriber_components_pid_file}"
    echo "transcriber.components.started=1"
    print_transcriber_components_status
}

remove_transcriber_dependencies() {
    rm -rf "${transcriber_tools_dir}"
    rm -f "${transcriber_pid_file}" "${transcriber_runtime_file}" "${transcriber_stop_file}" "${transcriber_pause_file}" "${transcriber_components_file}" "${transcriber_components_pid_file}"
    rm -rf "${state_dir}/transcriber-work"
}

remove_transcriber_component() {
    component="${1}"

    case "${component}" in
        whisper_cli)
            target=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
            ;;
        base_model)
            target=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
            ;;
        tinydiarize_model)
            target=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")
            ;;
        *)
            echo "Unknown transcriber component: ${component}" >&2
            return 1
            ;;
    esac

    case "${target}" in
        "${transcriber_tools_dir}"/*)
            rm -f "${target}"
            ;;
        *)
            echo "Refusing to remove a component outside the module tools directory: ${target}" >&2
            return 1
            ;;
    esac
    component_status_reset
    echo "removed.component=${component}"
}

path_free_bytes() {
    target="${1}"

    while [ ! -e "${target}" ] && [ "${target}" != "/" ]; do
        target=${target%/*}
        [ -n "${target}" ] || target="/"
    done

    df -k "${target}" 2>/dev/null | awk 'NR > 1 { available = $4 } END { if (available ~ /^[0-9]+$/) printf "%.0f", available * 1024 }'
}

path_is_writable() {
    target="${1}"

    while [ ! -e "${target}" ] && [ "${target}" != "/" ]; do
        target=${target%/*}
        [ -n "${target}" ] || target="/"
    done

    [ -w "${target}" ]
}

print_directory_health() {
    health_prefix="${1}"
    health_path="${2}"

    echo "${health_prefix}.path=${health_path}"
    echo "${health_prefix}.exists=$(bool_string test -d "${health_path}")"
    echo "${health_prefix}.writable=$(bool_string path_is_writable "${health_path}")"
    echo "${health_prefix}.free_bytes=$(path_free_bytes "${health_path}")"
}

print_status() {
    ensure_dirs
    clear_stale_pid
    status_output_dir=$(config_get_or_default output.dir "${default_output_dir}")
    status_transcriber_output_dir=$(config_get_or_default transcriber.output_dir "$(default_transcript_dir_for "${status_output_dir}")")
    status_whisper_path=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
    status_model_path=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
    status_tdrz_model_path=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")

    echo "module.id=${module_id}"
    echo "module.version=$(module_prop version)"
    echo "module.path=${mod_dir}"
    echo "recording.enabled=$(bool_string config_is_enabled recording.enabled 0)"
    echo "output.dir=${status_output_dir}"
    echo "recording.min_duration=$(config_get_or_default recording.min_duration 0)"
    echo "recording.log_enabled=$(bool_string config_is_enabled recording.log_enabled 1)"
    echo "recording.stereo=$(bool_string config_is_enabled recording.stereo 1)"
    echo "recording.format=$(config_get_or_default recording.format wav)"
    echo "recording.available_formats=$(config_get_or_default recording.available_formats wav)"
    echo "recording.detected_mode=$(config_get_or_default recording.detected_mode stereo)"
    echo "recording.voice_call_stereo_supported=$(config_get_or_default recording.voice_call_stereo_supported 1)"
    echo "recording.voice_call_probe_status=$(config_get_or_default recording.voice_call_probe_status assumed_stereo)"
    echo "recording.voice_call_probe_note=$(config_get_or_default recording.voice_call_probe_note "Stereo VOICE_CALL check unavailable; assuming stereo until overridden")"
    echo "notifications.enabled=$(bool_string config_is_enabled notifications.enabled 1)"
    echo "debug.enabled=$(bool_string config_is_enabled debug.enabled 0)"
    echo "transcriber.enabled=$(bool_string config_is_enabled transcriber.enabled 0)"
    echo "transcriber.output_dir=${status_transcriber_output_dir}"
    echo "transcriber.language=$(config_get_or_default transcriber.language en)"
    echo "transcriber.output_format=$(config_get_or_default transcriber.output_format txt)"
    echo "transcriber.speaker_self_name=$(config_get_or_default transcriber.speaker_self_name "Speaker A")"
    echo "transcriber.speaker_remote_name=$(config_get_or_default transcriber.speaker_remote_name "Speaker B")"
    echo "transcriber.whisper_path=${status_whisper_path}"
    echo "transcriber.model_path=${status_model_path}"
    echo "transcriber.tinydiarize_model_path=${status_tdrz_model_path}"
    echo "transcriber.device_abi=$(detect_android_abi)"
    echo "transcriber.whisper_manifest_url=$(config_get_or_default transcriber.whisper_manifest_url "${default_whisper_manifest_url}")"
    echo "transcriber.model_url=$(config_get_or_default transcriber.model_url "${default_model_url}")"
    echo "transcriber.tinydiarize_model_url=$(config_get_or_default transcriber.tinydiarize_model_url "${default_tinydiarize_model_url}")"
    if config_is_enabled recording.stereo 1; then
        status_component_estimate=$((default_whisper_size + default_model_size))
    else
        status_component_estimate=$((default_whisper_size + default_tinydiarize_model_size))
    fi
    echo "transcriber.components.estimated_bytes=${status_component_estimate}"
    print_directory_health output.dir "${status_output_dir}"
    print_directory_health transcriber.output_dir "${status_transcriber_output_dir}"
    echo "daemon.running=$(bool_string is_daemon_running)"
    echo "daemon.pid=$(cat "${pid_file}" 2>/dev/null || true)"
    clear_stale_transcriber_pid
    echo "transcriber.running=$(bool_string is_transcriber_running)"
    echo "transcriber.pid=$(cat "${transcriber_pid_file}" 2>/dev/null || true)"

    if [ -f "${runtime_file}" ]; then
        cat "${runtime_file}"
    fi
}

#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2026 wjdob
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

root_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
fixtures_dir="${root_dir}/scripts/fixtures/shell"
temp_dir=$(mktemp -d)
trap 'rm -rf "${temp_dir}"' EXIT

fail() {
    printf 'Shell contract failed: %s\n' "$*" >&2
    exit 1
}

assert_contains() {
    file=${1}
    expected=${2}
    grep -Fq "${expected}" "${file}" || fail "missing '${expected}' in ${file}"
}

prepare_module() {
    module_dir=${1}
    mkdir -p "${module_dir}/tools" "${module_dir}/.state" "${module_dir}/.config"
    cp "${root_dir}/app/magisk/action.sh" "${module_dir}/action.sh"
    cp "${root_dir}/app/magisk/module_common.sh" "${module_dir}/module_common.sh"
    cp "${root_dir}/app/magisk/customize.sh" "${module_dir}/customize.sh"
    touch "${module_dir}/tools/bcr-headless.apk"
    cat > "${module_dir}/module.prop" <<'EOF'
id=bcr.headless
version=v1.3.0
EOF
}

fake_bin="${temp_dir}/bin"
mkdir -p "${fake_bin}"
cat > "${fake_bin}/app_process" <<EOF
#!/usr/bin/env bash
case " \$* " in
    *" transcriber status "*) cat "${fixtures_dir}/transcriber-status.json" ;;
    *" transcriber library "*) cat "${fixtures_dir}/library-page.json" ;;
    *" recording-capability "*)
        printf '%s\n' \
            'recording.available_formats=wav,ogg,m4a' \
            'recording.detected_mode=stereo' \
            'recording.voice_call_stereo_supported=1' \
            'recording.voice_call_probe_status=supported' \
            'recording.voice_call_probe_note=Stereo capture available'
        ;;
    *) printf 'unexpected helper command: %s\n' "\$*" >&2; exit 2 ;;
esac
EOF
chmod +x "${fake_bin}/app_process"

config_module="${temp_dir}/config-module"
config_bin="${temp_dir}/config-bin"
ksud_calls="${temp_dir}/ksud-calls"
prepare_module "${config_module}"
mkdir -p "${config_bin}"
cat > "${config_bin}/ksud" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "${ksud_calls}"
if [[ "\${1:-}" == module && "\${2:-}" == config && "\${3:-}" == get ]]; then
    printf 'native-%s' "\${4:-unknown}"
fi
EOF
chmod +x "${config_bin}/ksud"
printf 'local-output' > "${config_module}/.config/output.dir"
config_value=$(PATH="${config_bin}:${fake_bin}:${PATH}" sh "${config_module}/action.sh" config get output.dir)
[[ "${config_value}" == local-output ]] || fail 'local config mirror was not preferred'
[[ ! -e "${ksud_calls}" ]] || fail 'ksud was invoked for a locally mirrored setting'
rm -f "${config_module}/.config/output.dir"
config_value=$(PATH="${config_bin}:${fake_bin}:${PATH}" sh "${config_module}/action.sh" config get output.dir)
[[ "${config_value}" == native-output.dir ]] || fail 'KernelSU config fallback was not read'
[[ $(cat "${config_module}/.config/output.dir") == native-output.dir ]] || fail 'KernelSU fallback was not cached locally'

runtime_module="${temp_dir}/runtime-module"
media_dir="${temp_dir}/media"
prepare_module "${runtime_module}"
mkdir -p "${media_dir}/transcripts"
printf '%s' "${media_dir}" > "${runtime_module}/.config/output.dir"
printf '%s' "${media_dir}/transcripts" > "${runtime_module}/.config/transcriber.output_dir"
printf '1' > "${runtime_module}/.config/transcriber.enabled"
cp "${fixtures_dir}/components-ready.env" "${runtime_module}/.state/transcriber-components.env"

snapshot_output="${temp_dir}/snapshot.txt"
PATH="${fake_bin}:${PATH}" sh "${runtime_module}/action.sh" \
    ui-snapshot transcriber 0 20 '' all newest all > "${snapshot_output}"
grep '^@@' "${snapshot_output}" > "${temp_dir}/snapshot.sections"
diff -u "${fixtures_dir}/ui-snapshot.sections" "${temp_dir}/snapshot.sections"
assert_contains "${snapshot_output}" 'component.whisper_cli.status=ready'
assert_contains "${snapshot_output}" '"selectedTranscriptExists": true,'

while IFS= read -r expected; do
    assert_contains "${snapshot_output}" "${expected}"
done < "${fixtures_dir}/path-health.expected"
grep -Eq '^output\.dir\.free_bytes=[0-9]+$' "${snapshot_output}" || \
    fail 'output free-space health is missing or invalid'
health_output="${temp_dir}/transcript-health.txt"
PATH="${fake_bin}:${PATH}" sh "${runtime_module}/action.sh" output-health transcripts > "${health_output}"
assert_contains "${health_output}" 'health.kind=transcripts'
assert_contains "${health_output}" "health.path=${media_dir}/transcripts"
assert_contains "${health_output}" 'health.writable=1'

legacy_module="${temp_dir}/legacy-module"
prepare_module "${legacy_module}"
printf '0' > "${legacy_module}/.config/output.write_metadata"
printf '1' > "${legacy_module}/.config/transcriber.auto_queue"
printf '1' > "${legacy_module}/.config/transcriber.auto_queue_require_charging"
printf '30' > "${legacy_module}/.config/transcriber.auto_queue_charge_delay_seconds"
printf '/sdcard/Download/custom-whisper.zip' > "${legacy_module}/.config/transcriber.whisper_local_path"
printf 'https://example.invalid/whisper-cli.zip' > "${legacy_module}/.config/transcriber.whisper_url"
printf 'https://example.invalid/tools.env' > "${legacy_module}/.config/transcriber.whisper_manifest_url"
printf 'https://example.invalid/model.bin' > "${legacy_module}/.config/transcriber.model_url"
printf 'https://example.invalid/tdrz.bin' > "${legacy_module}/.config/transcriber.tinydiarize_model_url"
PATH="${fake_bin}:${PATH}" sh "${legacy_module}/action.sh" ui-snapshot recorder > /dev/null

legacy_actual="${temp_dir}/legacy-config.actual"
for key in recording.log_enabled debug.enabled transcriber.speaker_remote_name; do
    printf '%s=' "${key}" >> "${legacy_actual}"
    cat "${legacy_module}/.config/${key}" >> "${legacy_actual}"
    printf '\n' >> "${legacy_actual}"
done
diff -u "${fixtures_dir}/legacy-config.expected" "${legacy_actual}"
for removed in transcriber.auto_queue transcriber.auto_queue_require_charging transcriber.auto_queue_charge_delay_seconds; do
    [[ ! -e "${legacy_module}/.config/${removed}" ]] || fail "legacy key ${removed} was not removed"
done
for removed in transcriber.whisper_local_path transcriber.whisper_url; do
    [[ ! -e "${legacy_module}/.config/${removed}" ]] || fail "unsupported source key ${removed} was not removed"
done
[[ $(cat "${legacy_module}/.config/transcriber.whisper_manifest_url") == 'https://github.com/wjdob/BCR-Headless/releases/download/transcriber-tools/transcriber-tools.env' ]] || fail 'manifest source was not restored'
[[ $(cat "${legacy_module}/.config/transcriber.model_url") == 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin' ]] || fail 'Whisper model source was not restored'
[[ $(cat "${legacy_module}/.config/transcriber.tinydiarize_model_url") == 'https://huggingface.co/akashmjn/tinydiarize-whisper.cpp/resolve/main/ggml-small.en-tdrz.bin' ]] || fail 'TinyDiarize source was not restored'

modules_root="${temp_dir}/modules"
current_module="${modules_root}/bcr.headless"
staged_module="${temp_dir}/staged-module"
user_media="${temp_dir}/user-media"
mkdir -p "${current_module}/.config" "${current_module}/.state" \
    "${current_module}/tools/transcriber" "${staged_module}/system/addon.d" "${user_media}/transcripts"
cp "${root_dir}/app/magisk/customize.sh" "${staged_module}/customize.sh"
cat > "${staged_module}/module.prop" <<'EOF'
id=bcr.headless
version=v1.3.0
EOF
printf '%s' "${user_media}" > "${current_module}/.config/output.dir"
printf 'native-cli' > "${current_module}/tools/transcriber/whisper-cli"
printf 'recording-history' > "${current_module}/.state/recording-log.json"
printf 'queue-state' > "${current_module}/.state/transcriber-queue.json"
printf 'runtime-state' > "${current_module}/.state/transcriber-runtime.json"
printf 'component-state' > "${current_module}/.state/transcriber-components.env"
printf 'manifest-state' > "${current_module}/.state/transcriber-tools.env"
printf 'daemon-log' > "${current_module}/.state/daemon.log"
printf 'transcriber-log' > "${current_module}/.state/transcriber.log"
printf 'keep recording' > "${user_media}/call.wav"
printf 'keep transcript' > "${user_media}/transcripts/call.txt"

MODPATH="${staged_module}" BCR_MODULES_DIR="${modules_root}" sh "${staged_module}/customize.sh"
while IFS= read -r relative; do
    [[ -f "${staged_module}/${relative}" ]] || fail "update did not preserve ${relative}"
    cmp "${current_module}/${relative}" "${staged_module}/${relative}" || \
        fail "preserved file changed: ${relative}"
done < "${fixtures_dir}/preserved-files.txt"
[[ ! -e "${staged_module}/.state/daemon.pid" ]] || fail 'stale daemon PID was preserved'
[[ $(cat "${user_media}/call.wav") == 'keep recording' ]] || fail 'recording media changed during update'
[[ $(cat "${user_media}/transcripts/call.txt") == 'keep transcript' ]] || fail 'transcript media changed during update'

printf 'Shell contract checks passed\n'

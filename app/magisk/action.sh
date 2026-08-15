#!/system/bin/sh

# SPDX-FileCopyrightText: 2026 wjdob
# SPDX-License-Identifier: GPL-3.0-only

. "${0%/*}/module_common.sh"

command="${1:-status}"

load_transcriber_context() {
    ensure_defaults
    recording_output_dir=$(config_get_or_default output.dir "${default_output_dir}")
    transcript_output_dir=$(config_get_or_default transcriber.output_dir "$(default_transcript_dir_for "${recording_output_dir}")")
    transcriber_language=$(config_get_or_default transcriber.language en)
    transcriber_format=$(config_get_or_default transcriber.output_format txt)
    transcriber_speaker_self_name=$(config_get_or_default transcriber.speaker_self_name "Speaker A")
    transcriber_speaker_remote_name=$(config_get_or_default transcriber.speaker_remote_name "Speaker B")
    whisper_path=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
    model_path=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
    tdrz_model_path=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")
}

print_snapshot_transcriber_status() {
    run_helper_foreground transcriber status \
        "${mod_dir}" \
        "${recording_output_dir}" \
        "${transcript_output_dir}" \
        "${whisper_path}" \
        "${model_path}" \
        "${tdrz_model_path}"
}

case "${command}" in
    status)
        print_status
        ;;
    ui-snapshot)
        view="${2:-recorder}"
        offset="${3:-0}"
        limit="${4:-80}"
        search="${5:-}"
        transcript_filter="${6:-all}"
        sort_order="${7:-newest}"
        channel_filter="${8:-all}"
        load_transcriber_context

        echo "@@META"
        echo "snapshot.view=${view}"
        echo "snapshot.generated_at=$(component_timestamp)"
        echo "@@STATUS"
        print_status

        case "${view}" in
            library)
                echo "@@RECORDINGS"
                run_helper_foreground transcriber library \
                    "${mod_dir}" "${recording_output_dir}" "${transcript_output_dir}" "${transcriber_format}" \
                    "${offset}" "${limit}" "${search}" "${transcript_filter}" "${sort_order}" "${channel_filter}"
                ;;
            transcriber)
                echo "@@TRANSCRIBER"
                print_snapshot_transcriber_status
                echo "@@COMPONENTS"
                print_transcriber_components_status
                echo "@@RECORDINGS"
                run_helper_foreground transcriber library \
                    "${mod_dir}" "${recording_output_dir}" "${transcript_output_dir}" "${transcriber_format}" \
                    "${offset}" "${limit}" "${search}" "${transcript_filter}" "${sort_order}" "${channel_filter}"
                ;;
            diagnostics)
                echo "@@TRANSCRIBER"
                print_snapshot_transcriber_status
                echo "@@COMPONENTS"
                print_transcriber_components_status
                ;;
            recorder)
                ;;
            *)
                echo "Unknown snapshot view: ${view}" >&2
                exit 1
                ;;
        esac
        echo "@@END"
        ;;
    start)
        start_daemon
        print_status
        ;;
    stop)
        stop_daemon
        print_status
        ;;
    restart|apply)
        stop_daemon
        start_daemon
        print_status
        ;;
    probe)
        ensure_defaults
        run_helper_foreground probe \
            "${mod_dir}" \
            "$(config_get_or_default output.dir "${default_output_dir}")"
        ;;
    open-output-dir)
        ensure_defaults
        run_helper_foreground open-output-dir \
            "$(config_get_or_default output.dir "${default_output_dir}")"
        ;;
    open-transcript-output-dir)
        ensure_defaults
        recording_output_dir=$(config_get_or_default output.dir "${default_output_dir}")
        transcript_output_dir=$(config_get_or_default transcriber.output_dir "$(default_transcript_dir_for "${recording_output_dir}")")
        run_helper_foreground open-output-dir \
            "${transcript_output_dir}"
        ;;
    output-health)
        ensure_defaults
        health_target="${2:-recordings}"
        recording_output_dir=$(config_get_or_default output.dir "${default_output_dir}")
        case "${health_target}" in
            recordings)
                health_path="${recording_output_dir}"
                ;;
            transcripts)
                health_path=$(config_get_or_default transcriber.output_dir "$(default_transcript_dir_for "${recording_output_dir}")")
                ;;
            *)
                echo "Usage: $0 output-health [recordings|transcripts]" >&2
                exit 1
                ;;
        esac
        echo "health.kind=${health_target}"
        print_directory_health health "${health_path}"
        ;;
    open-recording)
        shift || true
        path="${*}"

        if [ -z "${path}" ]; then
            echo "Usage: $0 open-recording <path>" >&2
            exit 1
        fi

        run_helper_foreground open-recording "${path}"
        ;;
    recording-log)
        subcommand="${2:-list}"

        case "${subcommand}" in
            list)
                print_recording_log
                ;;
            clear)
                clear_recording_log
                print_recording_log
                ;;
            *)
                echo "Usage: $0 recording-log [list|clear]" >&2
                exit 1
                ;;
        esac
        ;;
    transcriber)
        subcommand="${2:-status}"
        load_transcriber_context

        case "${subcommand}" in
            status)
                run_helper_foreground transcriber status \
                    "${mod_dir}" \
                    "${recording_output_dir}" \
                    "${transcript_output_dir}" \
                    "${whisper_path}" \
                    "${model_path}" \
                    "${tdrz_model_path}"
                ;;
            list)
                run_helper_foreground transcriber list \
                    "${mod_dir}" \
                    "${recording_output_dir}" \
                    "${transcript_output_dir}" \
                    "${transcriber_format}"
                ;;
            library)
                offset="${3:-0}"
                limit="${4:-80}"
                search="${5:-}"
                transcript_filter="${6:-all}"
                sort_order="${7:-newest}"
                channel_filter="${8:-all}"
                run_helper_foreground transcriber library \
                    "${mod_dir}" "${recording_output_dir}" "${transcript_output_dir}" "${transcriber_format}" \
                    "${offset}" "${limit}" "${search}" "${transcript_filter}" "${sort_order}" "${channel_filter}"
                ;;
            enqueue)
                if ! config_is_enabled transcriber.enabled 0; then
                    echo "transcriber.disabled=1" >&2
                    exit 1
                fi

                conflict_policy="${3:-cancel}"
                shift 3 || true

                if [ "$#" -eq 0 ]; then
                    echo "Usage: $0 transcriber enqueue <overwrite|skip|cancel> <recording>..." >&2
                    exit 1
                fi

                run_helper_foreground transcriber enqueue \
                    "${mod_dir}" \
                    "${transcript_output_dir}" \
                    "${transcriber_language}" \
                    "${transcriber_format}" \
                    "${conflict_policy}" \
                    "${transcriber_speaker_self_name}" \
                    "${transcriber_speaker_remote_name}" \
                    "$@"
                ;;
            pause)
                run_helper_foreground transcriber control "${mod_dir}" pause
                ;;
            resume)
                run_helper_foreground transcriber control "${mod_dir}" resume
                start_transcriber_worker
                ;;
            stop)
                run_helper_foreground transcriber control "${mod_dir}" stop
                ;;
            clear)
                run_helper_foreground transcriber control "${mod_dir}" clear
                ;;
            remove)
                job_id="${3:-}"

                if [ -z "${job_id}" ]; then
                    echo "Usage: $0 transcriber remove <job_id>" >&2
                    exit 1
                fi

                run_helper_foreground transcriber control "${mod_dir}" remove "${job_id}"
                ;;
            retry|move-up|move-down)
                job_id="${3:-}"

                if [ -z "${job_id}" ]; then
                    echo "Usage: $0 transcriber ${subcommand} <job_id>" >&2
                    exit 1
                fi

                run_helper_foreground transcriber control "${mod_dir}" "${subcommand}" "${job_id}"
                ;;
            start-worker)
                start_transcriber_worker
                ;;
            install-deps)
                prepare_profile="${3:-stereo}"
                install_transcriber_dependencies "${prepare_profile}"
                ;;
            components-status)
                print_transcriber_components_status
                ;;
            components-refresh-metadata)
                refresh_transcriber_components_metadata
                ;;
            components-reset)
                component_status_reset
                print_transcriber_components_status
                ;;
            remove-deps)
                remove_transcriber_dependencies
                print_transcriber_components_status
                ;;
            remove-component)
                component="${3:-}"
                if [ -z "${component}" ]; then
                    echo "Usage: $0 transcriber remove-component <whisper_cli|base_model|tinydiarize_model>" >&2
                    exit 1
                fi
                remove_transcriber_component "${component}"
                print_transcriber_components_status
                ;;
            open-transcript)
                shift 2 || true
                path="${*}"

                if [ -z "${path}" ]; then
                    echo "Usage: $0 transcriber open-transcript <path>" >&2
                    exit 1
                fi

                run_helper_foreground transcriber open-transcript "${path}"
                ;;
            preview)
                path="${3:-}"
                max_chars="${4:-12000}"
                if [ -z "${path}" ]; then
                    echo "Usage: $0 transcriber preview <path> [max_chars]" >&2
                    exit 1
                fi
                run_helper_foreground transcriber preview "${path}" "${max_chars}"
                ;;
            logs)
                tail -n 200 "${transcriber_log}" 2>/dev/null || true
                ;;
            *)
                echo "Usage: $0 transcriber [status|list|library|enqueue|pause|resume|stop|clear|remove|retry|move-up|move-down|start-worker|install-deps|components-status|components-refresh-metadata|components-reset|remove-deps|remove-component|open-transcript|preview|logs]" >&2
                exit 1
                ;;
        esac
        ;;
    logs)
        tail -n 200 "${daemon_log}" 2>/dev/null || true
        ;;
    defaults|reset-config)
        reset_defaults
        print_status
        ;;
    config)
        subcommand="${2:-}"

        case "${subcommand}" in
            list)
                # Keep configuration behind action.sh so the exact same control
                # surface works from KernelSU's built-in WebUI and from
                # standalone KSUWebUI apps used on Magisk.
                config_list
                ;;
            get)
                key="${3:-}"

                if [ -z "${key}" ]; then
                    echo "Usage: $0 config get <key>" >&2
                    exit 1
                fi

                if ! config_get "${key}"; then
                    exit 1
                fi
                printf '\n'
                ;;
            set)
                key="${3:-}"

                if [ -z "${key}" ]; then
                    echo "Usage: $0 config set <key> <value>" >&2
                    exit 1
                fi

                shift 3 || true
                value="${*}"

                config_set "${key}" "${value}"
                refresh_description
                printf '%s=%s\n' "${key}" "$(config_get_or_default "${key}" "")"
                ;;
            delete|unset)
                key="${3:-}"

                if [ -z "${key}" ]; then
                    echo "Usage: $0 config delete <key>" >&2
                    exit 1
                fi

                config_delete "${key}"
                refresh_description
                ;;
            *)
                echo "Usage: $0 config [list|get|set|delete] ..." >&2
                exit 1
                ;;
        esac
        ;;
    *)
        echo "Usage: $0 [status|ui-snapshot|start|stop|restart|apply|probe|output-health|open-output-dir|open-transcript-output-dir|open-recording|recording-log|transcriber|logs|defaults|reset-config|config]" >&2
        exit 1
        ;;
esac

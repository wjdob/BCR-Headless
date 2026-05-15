#!/system/bin/sh

# SPDX-FileCopyrightText: 2026 wjdob
# SPDX-License-Identifier: GPL-3.0-only

. "${0%/*}/module_common.sh"

command="${1:-status}"

case "${command}" in
    status)
        print_status
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
            "$(config_get_or_default output.dir /sdcard/Recordings/BCR)"
        ;;
    open-output-dir)
        ensure_defaults
        run_helper_foreground open-output-dir \
            "$(config_get_or_default output.dir /sdcard/Recordings/BCR)"
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
        ensure_defaults

        recording_output_dir=$(config_get_or_default output.dir /sdcard/Recordings/BCR)
        transcript_output_dir=$(config_get_or_default transcriber.output_dir "${recording_output_dir}/transcripts")
        transcriber_language=$(config_get_or_default transcriber.language en)
        transcriber_format=$(config_get_or_default transcriber.output_format txt)
        whisper_path=$(config_get_or_default transcriber.whisper_path "${transcriber_tools_dir}/whisper-cli")
        model_path=$(config_get_or_default transcriber.model_path "${transcriber_tools_dir}/models/ggml-base.en.bin")
        tdrz_model_path=$(config_get_or_default transcriber.tinydiarize_model_path "${transcriber_tools_dir}/models/ggml-small.en-tdrz.bin")

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
                    "$@"
                start_transcriber_worker
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
            start-worker)
                start_transcriber_worker
                ;;
            install-deps)
                install_transcriber_dependencies
                ;;
            components-status)
                print_transcriber_components_status
                ;;
            remove-deps)
                remove_transcriber_dependencies
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
            logs)
                tail -n 200 "${transcriber_log}" 2>/dev/null || true
                ;;
            *)
                echo "Usage: $0 transcriber [status|list|enqueue|pause|resume|stop|clear|remove|start-worker|install-deps|components-status|remove-deps|open-transcript|logs]" >&2
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
        echo "Usage: $0 [status|start|stop|restart|apply|probe|open-output-dir|open-recording|recording-log|transcriber|logs|defaults|reset-config|config]" >&2
        exit 1
        ;;
esac

#!/system/bin/sh

# SPDX-FileCopyrightText: 2026 wjdob
# SPDX-License-Identifier: GPL-3.0-only

source "${0%/*}/module_common.sh"

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
        echo "Usage: $0 [status|start|stop|restart|apply|probe|open-output-dir|open-recording|recording-log|logs|defaults|reset-config|config]" >&2
        exit 1
        ;;
esac

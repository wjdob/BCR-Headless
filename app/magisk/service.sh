#!/system/bin/sh

# SPDX-FileCopyrightText: 2023 Andrew Gunnerson
# SPDX-FileCopyrightText: 2026 wjdob
# SPDX-License-Identifier: GPL-3.0-only

source "${0%/*}/module_common.sh"

# Keep late_start service work intentionally small: ensure defaults, refresh the
# manager-visible status, and launch the headless daemon if recording is enabled.
ensure_defaults
clear_stale_pid
start_daemon

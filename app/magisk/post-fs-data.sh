#!/system/bin/sh

# SPDX-FileCopyrightText: 2023 Andrew Gunnerson
# SPDX-FileCopyrightText: 2026 wjdob
# SPDX-License-Identifier: GPL-3.0-only

source "${0%/*}/module_common.sh"

# The headless pivot avoids package-manager and /system mutations entirely.
# post-fs-data now only prepares module-local state and refreshes the manager
# description once KernelSU's temporary config store has been cleared.
ensure_dirs
ensure_defaults
clear_stale_pid
refresh_description

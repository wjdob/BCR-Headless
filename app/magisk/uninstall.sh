#!/system/bin/sh

# SPDX-FileCopyrightText: 2026 wjdob
# SPDX-License-Identifier: GPL-3.0-only

# Magisk/KernelSU run this from the module directory during removal. Keep
# optional transcriber assets tied to the module lifecycle so downloaded models
# and native helpers do not remain after BCR Headless is gone.
mod_dir="${0%/*}"

rm -rf "${mod_dir}/tools/transcriber"
rm -rf "${mod_dir}/.state/transcriber-work"
rm -f \
    "${mod_dir}/.state/transcriber.pid" \
    "${mod_dir}/.state/transcriber-runtime.json" \
    "${mod_dir}/.state/transcriber.stop" \
    "${mod_dir}/.state/transcriber.pause"

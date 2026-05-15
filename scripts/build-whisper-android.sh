#!/usr/bin/env bash

# SPDX-FileCopyrightText: 2026 wjdob
# SPDX-License-Identifier: GPL-3.0-only

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_dir=$(cd -- "${script_dir}/.." && pwd)

# shellcheck source=transcriber-tools.env
source "${script_dir}/transcriber-tools.env"
WHISPER_CPP_REF="${WHISPER_CPP_REF_OVERRIDE:-${WHISPER_CPP_REF}}"

work_dir="${1:-${repo_dir}/build/transcriber-tools-work}"
out_dir="${2:-${repo_dir}/build/transcriber-tools}"
whisper_dir="${work_dir}/whisper.cpp"
asset_base_url="${ASSET_BASE_URL:-https://github.com/wjdob/BCR-Headless/releases/download/${TOOLS_RELEASE_TAG}}"

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}" ]]; then
        ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/${ANDROID_NDK_VERSION}"
    else
        echo "ANDROID_NDK_HOME is not set and ${ANDROID_NDK_VERSION} was not found under ANDROID_HOME" >&2
        exit 1
    fi
fi

cmake_bin="${CMAKE_BIN:-cmake}"
ninja_bin="${NINJA_BIN:-ninja}"
zip_bin="${ZIP_BIN:-zip}"
manifest="${out_dir}/transcriber-tools.env"

rm -rf "${out_dir}"
mkdir -p "${work_dir}" "${out_dir}"

if [[ ! -d "${whisper_dir}/.git" ]]; then
    git clone "${WHISPER_CPP_REPO}" "${whisper_dir}"
fi

git -C "${whisper_dir}" fetch --tags --force origin
git -C "${whisper_dir}" checkout --force "${WHISPER_CPP_REF}"
git -C "${whisper_dir}" submodule update --init --recursive

whisper_commit=$(git -C "${whisper_dir}" rev-parse HEAD)
generated_at=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

cat > "${manifest}" <<EOF
schema=1
tool=whisper-cli
whisper_cpp_repo=${WHISPER_CPP_REPO}
whisper_cpp_ref=${WHISPER_CPP_REF}
whisper_cpp_commit=${whisper_commit}
android_platform=${ANDROID_PLATFORM}
build=cpu-generic
generated_at=${generated_at}
EOF

for abi in ${ANDROID_ABIS}; do
    build_dir="${work_dir}/build-${abi}"
    package_root="${work_dir}/package-${abi}"
    package_name="whisper-cli-android-${abi}.zip"
    package_path="${out_dir}/${package_name}"
    cli_path="${build_dir}/bin/whisper-cli"

    rm -rf "${build_dir}" "${package_root}"

    "${cmake_bin}" -S "${whisper_dir}" -B "${build_dir}" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="${ninja_bin}" \
        -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="${abi}" \
        -DANDROID_PLATFORM="${ANDROID_PLATFORM}" \
        -DANDROID_STL=c++_static \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DWHISPER_BUILD_EXAMPLES=ON \
        -DWHISPER_BUILD_TESTS=OFF \
        -DWHISPER_BUILD_SERVER=OFF \
        -DWHISPER_SDL2=OFF \
        -DWHISPER_FFMPEG=OFF \
        -DWHISPER_CURL=OFF \
        -DGGML_NATIVE=OFF \
        -DGGML_OPENMP=OFF \
        -DGGML_VULKAN=OFF \
        -DGGML_BLAS=OFF \
        -DGGML_CUDA=OFF

    "${cmake_bin}" --build "${build_dir}" --target whisper-cli --parallel

    if [[ ! -x "${cli_path}" ]]; then
        echo "Missing built whisper-cli for ${abi}: ${cli_path}" >&2
        exit 1
    fi

    strip_bin="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    if [[ -x "${strip_bin}" ]]; then
        "${strip_bin}" "${cli_path}" || true
    fi

    mkdir -p "${package_root}"
    cp "${cli_path}" "${package_root}/whisper-cli"
    chmod 0755 "${package_root}/whisper-cli"
    cat > "${package_root}/transcriber-tool.properties" <<EOF
tool=whisper-cli
abi=${abi}
whisper_cpp_repo=${WHISPER_CPP_REPO}
whisper_cpp_ref=${WHISPER_CPP_REF}
whisper_cpp_commit=${whisper_commit}
android_platform=${ANDROID_PLATFORM}
build=cpu-generic
EOF

    (cd "${package_root}" && "${zip_bin}" -9 -r "${package_path}" whisper-cli transcriber-tool.properties)

    sha256=$(sha256sum "${package_path}" | awk '{ print $1 }')
    size=$(stat -c '%s' "${package_path}")
    {
        echo "abi.${abi}.url=${asset_base_url}/${package_name}"
        echo "abi.${abi}.sha256=${sha256}"
        echo "abi.${abi}.size=${size}"
    } >> "${manifest}"
done

sha256sum "${out_dir}"/*.zip "${manifest}" > "${out_dir}/SHA256SUMS"
echo "Wrote transcriber tool packages to ${out_dir}"

#!/usr/bin/env bash
#
# Copyright 2025 Adobe. All rights reserved.
# This file is licensed to you under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License. You may obtain a copy
# of the License at http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under
# the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
# OF ANY KIND, either express or implied. See the License for the specific language
# governing permissions and limitations under the License.
#
# Ensures Android SDK Platform 37 is available for the testapp (compileSdk 37).
# GitHub-hosted runners ship with older platforms only; AGP 8.2 expects platforms/android-37.

set -euo pipefail

readonly PLATFORM_DIR_NAME="android-37"
readonly SDK_PACKAGE="platforms;android-37"

resolve_sdk_root() {
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        echo "$ANDROID_SDK_ROOT"
    elif [[ -n "${ANDROID_HOME:-}" ]]; then
        echo "$ANDROID_HOME"
    elif [[ -d "/usr/local/lib/android/sdk" ]]; then
        echo "/usr/local/lib/android/sdk"
    else
        echo "Unable to locate Android SDK. Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
        exit 1
    fi
}

resolve_sdkmanager() {
    local sdk_root="$1"
    local candidate

    for candidate in \
        "$sdk_root/cmdline-tools/latest/bin/sdkmanager" \
        "$sdk_root/cmdline-tools/bin/sdkmanager" \
        "$sdk_root/tools/bin/sdkmanager"; do
        if [[ -x "$candidate" ]]; then
            echo "$candidate"
            return 0
        fi
    done

    echo "sdkmanager not found under $sdk_root" >&2
    exit 1
}

platform_is_usable() {
    local platforms_dir="$1"
    [[ -f "$platforms_dir/$PLATFORM_DIR_NAME/android.jar" ]] &&
        [[ -f "$platforms_dir/$PLATFORM_DIR_NAME/source.properties" ]]
}

link_platform_from_37_0() {
    local platforms_dir="$1"

    if [[ ! -d "$platforms_dir/android-37.0" ]]; then
        return 1
    fi

    echo "Linking $PLATFORM_DIR_NAME to android-37.0 for AGP 8.2 compatibility..."
    ln -sfn android-37.0 "$platforms_dir/$PLATFORM_DIR_NAME"
}

install_platform() {
    local sdk_root="$1"
    local sdkmanager platforms_dir

    platforms_dir="$sdk_root/platforms"
    if platform_is_usable "$platforms_dir"; then
        echo "Android SDK platform $PLATFORM_DIR_NAME already installed."
        return 0
    fi

    if link_platform_from_37_0 "$platforms_dir" && platform_is_usable "$platforms_dir"; then
        echo "Android SDK platform $PLATFORM_DIR_NAME is ready."
        return 0
    fi

    sdkmanager="$(resolve_sdkmanager "$sdk_root")"
    echo "Installing Android SDK platform $SDK_PACKAGE for testapp..."

    yes | "$sdkmanager" --sdk_root="$sdk_root" --licenses >/dev/null
    yes | "$sdkmanager" --sdk_root="$sdk_root" "$SDK_PACKAGE" || true

    if ! platform_is_usable "$platforms_dir"; then
        link_platform_from_37_0 "$platforms_dir" || true
    fi

    if ! platform_is_usable "$platforms_dir"; then
        echo "Failed to install Android SDK platform $PLATFORM_DIR_NAME." >&2
        exit 1
    fi

    echo "Android SDK platform $PLATFORM_DIR_NAME is ready."
}

main() {
    install_platform "$(resolve_sdk_root)"
}

main "$@"

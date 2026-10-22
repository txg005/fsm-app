#!/bin/sh
# Uses the standard hosted Ubuntu Android SDK; no user local.properties/.env.
set -eu
: "${ANDROID_HOME:?Hosted Android SDK is required}"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
test -x "$SDKMANAGER"
yes | "$SDKMANAGER" --licenses >/dev/null
"$SDKMANAGER" 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools'

#!/usr/bin/env bash
# Toolchain paths for this repo. Usage: `source scripts/env.sh`
# JDK 21 comes from Homebrew (openjdk@21, keg-only); the SDK lives in the standard Android Studio location.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
export APP_ID="com.hackathon.recall"

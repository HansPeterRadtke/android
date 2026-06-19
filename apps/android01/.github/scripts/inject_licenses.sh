#!/bin/bash
set -e

LICENSE_DIR="$ANDROID_HOME/licenses"
echo "Creating license directory: $LICENSE_DIR"
mkdir -p "$LICENSE_DIR"
echo "Writing android-sdk-license"
echo 8933bad161af4178b1185d1a37fbf41ea5269c55 > "$LICENSE_DIR/android-sdk-license"
echo "Writing android-sdk-preview-license"
echo d56f5187479451eabf01fb78af6dfcb131a6481e > "$LICENSE_DIR/android-sdk-preview-license"
echo "License injection completed."
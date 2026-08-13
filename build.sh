#!/usr/bin/env sh

set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR" || exit 1

echo "Building RemoteBox Java..."
if ! mvn clean package; then
    echo
    echo "Build failed."
    exit 1
fi

echo
echo "Build completed successfully."
echo "JAR: $SCRIPT_DIR/target/remotebox-java-1.0.0.jar"

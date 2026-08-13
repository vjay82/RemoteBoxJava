#!/usr/bin/env sh

set -u

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR" || exit 1

JAR="target/remotebox-java-1.0.0.jar"

if [ ! -f "$JAR" ]; then
    echo "No built JAR was found. Building RemoteBox Java first..."
    if ! sh "$SCRIPT_DIR/build.sh"; then
        echo
        echo "The application could not be started because the build failed."
        exit 1
    fi
fi

echo "Starting RemoteBox Java..."
exec java -jar "$JAR"

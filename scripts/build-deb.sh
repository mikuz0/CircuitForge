#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"

APP_NAME="CircuitForge-jfx"
APP_VERSION="1.0.0"
MAIN_CLASS="ru.circuitforge.app.MainApp"

APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$APP_DIR"

echo "=== Building application with Gradle ==="
./gradlew clean build installDist --no-daemon

APPDIR="build/install/CircuitForge"
if [ ! -d "$APPDIR" ]; then
    echo "ERROR: $APPDIR not found"
    exit 1
fi

echo "Application directory: $APPDIR"
MAIN_JAR=$(ls "$APPDIR/lib/circuitforge-"*.jar 2>/dev/null | head -n 1)
if [ -z "$MAIN_JAR" ]; then
    echo "ERROR: Main jar not found"
    ls -la "$APPDIR/lib/"
    exit 1
fi
echo "Main jar: $MAIN_JAR"

DEST_DIR="build/dist"
mkdir -p "$DEST_DIR"
rm -f "$DEST_DIR"/*.deb

echo "=== Cleaning up compile-only dependencies for jpackage ==="
# Удаляем библиотеку аннотаций, которая мешает jlink.
# Она нужна только компилятору, на runtime она не требуется.
rm -f "$APPDIR/lib/annotations-"*.jar

echo "=== Building .deb with jpackage ==="
jpackage \
    --type deb \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --input "$APPDIR/lib" \
    --main-jar "$(basename "$MAIN_JAR")" \
    --main-class "$MAIN_CLASS" \
    --module-path "$APPDIR/lib" \
        --add-modules javafx.controls,javafx.base,javafx.graphics,javafx.fxml \
    --icon "scripts/icon.png" \
    --linux-shortcut \
    --linux-menu-group "Development" \
    --dest "$DEST_DIR"

echo ""
echo "=== DONE ==="
ls -la "$DEST_DIR"/*.deb

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
[ -d "$APPDIR" ] || { echo "ERROR: $APPDIR not found"; exit 1; }

MAIN_JAR=$(ls "$APPDIR/lib/circuitforge-"*.jar 2>/dev/null | head -n 1)
[ -n "$MAIN_JAR" ] || { echo "ERROR: Main jar not found"; ls -la "$APPDIR/lib/"; exit 1; }
echo "Main jar: $(basename "$MAIN_JAR")"

DEST_DIR="build/dist"
mkdir -p "$DEST_DIR"
rm -f "$DEST_DIR"/*.deb

RUNTIME_DIR="build/runtime"
rm -rf "$RUNTIME_DIR"

# ВАЖНО: java.logging ОБЯЗАТЕЛЕН — JSVG использует java.util.logging внутри себя,
# хотя в вашем коде нет import java.util.logging.*
echo "=== Building jlink runtime (java.base + java.logging + JavaFX) ==="
jlink \
    --module-path "$APPDIR/lib" \
    --add-modules java.base,java.logging,javafx.controls,javafx.fxml \
    --strip-debug \
    --no-header-files \
    --no-man-pages \
    --compress=zip-6 \
    --output "$RUNTIME_DIR"

[ -x "$RUNTIME_DIR/bin/java" ] || { echo "ERROR: jlink runtime not created"; exit 1; }
echo "Runtime size: $(du -sh "$RUNTIME_DIR" | cut -f1)"

# Проверяем, что java.logging действительно попал в runtime
if ! "$RUNTIME_DIR/bin/java" --list-modules | grep -q '^java.logging'; then
    echo "ERROR: java.logging missing in jlink runtime — JSVG упадёт с NoClassDefFoundError"
    exit 1
fi
echo "java.logging: OK"

echo "=== Building .deb with jpackage ==="
jpackage \
    --type deb \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --input "$APPDIR/lib" \
    --main-jar "$(basename "$MAIN_JAR")" \
    --main-class "$MAIN_CLASS" \
    --runtime-image "$RUNTIME_DIR" \
    --java-options "--enable-native-access=ALL-UNNAMED" \
    --icon "scripts/icon.png" \
    --linux-shortcut \
    --linux-menu-group "Development" \
    --dest "$DEST_DIR"

DEB_FILE=$(ls "$DEST_DIR"/*.deb 2>/dev/null | head -n 1)
[ -n "$DEB_FILE" ] || { echo "ERROR: .deb not produced"; exit 1; }

echo ""
echo "=== Verifying package contents ==="
PKG_LIST=$(dpkg-deb -c "$DEB_FILE")

check() {
    local pattern="$1" label="$2"
    if echo "$PKG_LIST" | grep -Eiq "$pattern"; then
        echo "  OK:   $label"
    else
        echo "  FAIL: $label (pattern: $pattern)"
        return 1
    fi
}

rc=0
check 'elk\.core'            'ELK core'           || rc=1
check 'elk\.alg\.layered'    'ELK layered'        || rc=1
check 'jsvg-2'               'JSVG core'          || rc=1
check 'jsvg-javafx'          'JSVG JavaFX'        || rc=1
check 'runtime/lib/modules'  'jlink runtime'      || rc=1
check 'bin/circuitforge-jfx' 'launcher'           || rc=1
check 'symbols/resistor\.svg' 'SVG symbols in jar' || rc=1

[ "$rc" -eq 0 ] || { echo "ERROR: package verification failed"; exit 1; }

echo ""
echo "=== DONE ==="
ls -lh "$DEB_FILE"
echo "Runtime size: $(du -sh "$RUNTIME_DIR" | cut -f1)"
echo "Package size: $(du -h "$DEB_FILE" | cut -f1)"

#!/usr/bin/env bash
# Build a portable Linux .AppImage from the Compose/jpackage app-image directory.
#
# Usage: package-appimage.sh <app-image-dir> <output-dir> <version> <arch>
#
# appimagetool is downloaded into $XDG_CACHE_HOME/thermling (default
# ~/.cache/thermling) on first use. Its AppImage bundles mksquashfs, so no
# system package is required. Set APPIMAGETOOL to use an existing binary.

set -euo pipefail

if [[ $# -ne 4 ]]; then
    echo "usage: $0 <app-image-dir> <output-dir> <version> <arch>" >&2
    exit 2
fi

APP_IMAGE_DIR=$(readlink -f "$1")
OUTPUT_DIR=$2
VERSION=$3
ARCH=$4

APP_NAME=Thermling
APP_ID=thermling

if [[ ! -x "$APP_IMAGE_DIR/bin/$APP_NAME" ]]; then
    echo "error: $APP_IMAGE_DIR does not look like a jpackage app image (missing bin/$APP_NAME)" >&2
    exit 1
fi

if [[ -n "${APPIMAGETOOL:-}" ]]; then
    TOOL=$APPIMAGETOOL
else
    CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/thermling"
    TOOL="$CACHE_DIR/appimagetool-$ARCH.AppImage"
    if [[ ! -x "$TOOL" ]]; then
        mkdir -p "$CACHE_DIR"
        URL="https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$ARCH.AppImage"
        echo "Downloading appimagetool from $URL"
        TMP="$TOOL.part"
        if command -v curl >/dev/null 2>&1; then
            curl -fL --retry 3 --progress-bar -o "$TMP" "$URL"
        elif command -v wget >/dev/null 2>&1; then
            wget -O "$TMP" "$URL"
        else
            echo "error: need curl or wget to download appimagetool" >&2
            exit 1
        fi
        chmod +x "$TMP"
        mv "$TMP" "$TOOL"
    fi
fi

if [[ ! -x "$TOOL" ]]; then
    echo "error: appimagetool is not executable: $TOOL" >&2
    exit 1
fi

APPDIR="$OUTPUT_DIR/$APP_NAME.AppDir"
OUTPUT="$OUTPUT_DIR/$APP_NAME-$VERSION-$ARCH.AppImage"

echo "Building AppDir at $APPDIR"
rm -rf "$APPDIR"
mkdir -p "$APPDIR"
cp -a "$APP_IMAGE_DIR/." "$APPDIR/"

ICON_SOURCE="$APPDIR/lib/$APP_NAME.png"
if [[ ! -f "$ICON_SOURCE" ]]; then
    ICON_SOURCE=$(find "$APPDIR" -maxdepth 3 -type f -name '*.png' | head -n 1)
fi
if [[ -z "$ICON_SOURCE" || ! -f "$ICON_SOURCE" ]]; then
    echo "error: no application icon found in $APP_IMAGE_DIR" >&2
    exit 1
fi

cp "$ICON_SOURCE" "$APPDIR/$APP_NAME.png"
mkdir -p "$APPDIR/usr/share/icons/hicolor/1024x1024/apps"
cp "$ICON_SOURCE" "$APPDIR/usr/share/icons/hicolor/1024x1024/apps/$APP_NAME.png"

cat > "$APPDIR/$APP_NAME.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$APP_NAME
Comment=Label composer for Nelko P21/P22 thermal printers
Exec=$APP_NAME
Icon=$APP_NAME
Terminal=false
Categories=Office;Utility;
StartupWMClass=$APP_NAME
X-AppImage-Version=$VERSION
EOF

mkdir -p "$APPDIR/usr/share/applications"
cp "$APPDIR/$APP_NAME.desktop" "$APPDIR/usr/share/applications/$APP_NAME.desktop"

cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
HERE=$(dirname "$(readlink -f "$0")")
exec "$HERE/bin/Thermling" "$@"
EOF
chmod +x "$APPDIR/AppRun"

mkdir -p "$OUTPUT_DIR"
rm -f "$OUTPUT"
export ARCH
echo "Packaging $OUTPUT"
APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "$OUTPUT"
chmod +x "$OUTPUT"

echo "Created $OUTPUT"

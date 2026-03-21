#!/bin/bash
set -e
JDK=/home/$USER/jdk/zulu21.42.19-ca-jdk21.0.7-linux_x64
export JAVA_HOME=$JDK
export PATH=$JDK/bin:/usr/local/bin:/usr/bin:/bin

PROJ="$(dirname "$(realpath "$0")")"
chmod +x "$PROJ/gradlew"
cd "$PROJ"

./gradlew :desktop:packageDeb

# Patch the .deb: add StartupWMClass to .desktop and register app icon in hicolor theme
DEB_FILE=$(find "$PROJ/dist" -name "*.deb" | head -1)
if [ -n "$DEB_FILE" ]; then
    PATCH_DIR=/tmp/photonlab-deb-patch
    rm -rf "$PATCH_DIR"
    dpkg-deb -R "$DEB_FILE" "$PATCH_DIR"

    # Add StartupWMClass so Wayland/GNOME taskbar matches the .desktop entry
    DESKTOP_FILE=$(find "$PATCH_DIR" -name "*.desktop" | head -1)
    if [ -n "$DESKTOP_FILE" ] && ! grep -q "StartupWMClass" "$DESKTOP_FILE"; then
        echo "StartupWMClass=photonlab" >> "$DESKTOP_FILE"
    fi

    # Register app icon in the hicolor icon theme (needed for store/launcher icon)
    POSTINST="$PATCH_DIR/DEBIAN/postinst"
    if ! grep -q "xdg-icon-resource install --context apps" "$POSTINST"; then
        sed -i 's|xdg-desktop-menu install|xdg-icon-resource install --novendor --context apps --size 256 /opt/photonlab/lib/photonlab.png photonlab\nxdg-desktop-menu install|' "$POSTINST"
    fi
    POSTRM="$PATCH_DIR/DEBIAN/postrm"
    if [ -f "$POSTRM" ] && ! grep -q "xdg-icon-resource uninstall --context apps" "$POSTRM"; then
        sed -i 's|xdg-desktop-menu uninstall|xdg-icon-resource uninstall --novendor --context apps --size 256 photonlab\nxdg-desktop-menu uninstall|' "$POSTRM" 2>/dev/null || true
    fi

    # Add AppStream metadata so GNOME Software shows the correct icon in the install dialog
    METAINFO_DIR="$PATCH_DIR/usr/share/metainfo"
    APPINFO_ICON_DIR="$PATCH_DIR/usr/share/app-info/icons/hicolor/64x64/apps"
    mkdir -p "$METAINFO_DIR" "$APPINFO_ICON_DIR"
    python3 -c "
from PIL import Image
Image.open('$PROJ/desktop/src/main/resources/photonlab_icon.png') \
    .resize((64, 64), Image.LANCZOS) \
    .save('$APPINFO_ICON_DIR/photonlab.png')
"
    cat > "$METAINFO_DIR/photonlab.appdata.xml" << 'APPSTREAM_EOF'
<?xml version="1.0" encoding="UTF-8"?>
<component type="desktop-application">
  <id>photonlab</id>
  <name>PhotonLab</name>
  <summary>Non-destructive photo editor</summary>
  <metadata_license>MIT</metadata_license>
  <description>
    <p>PhotonLab is a fast, non-destructive photo editor with real-time editing.</p>
  </description>
  <launchable type="desktop-id">photonlab-photonlab.desktop</launchable>
  <icon type="cached">photonlab.png</icon>
</component>
APPSTREAM_EOF

    # Patch Depends: replace t64-suffixed lib names with cross-version alternatives
    # jpackage on Ubuntu 24.04 generates libasound2t64 / libpng16-16t64 which don't
    # exist on older Ubuntu/Debian. Use OR dependencies so the package installs on both.
    CONTROL="$PATCH_DIR/DEBIAN/control"
    if [ -f "$CONTROL" ]; then
        sed -i \
            -e 's/libasound2t64/libasound2t64 | libasound2/g' \
            -e 's/libpng16-16t64/libpng16-16t64 | libpng16-16/g' \
            "$CONTROL"
    fi

    dpkg-deb --build --root-owner-group "$PATCH_DIR" "$DEB_FILE"
    rm -rf "$PATCH_DIR"
fi

echo "=== Build complete ==="
find "$PROJ/dist" -name "*.deb" 2>/dev/null

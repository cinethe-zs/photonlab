#!/bin/bash
set -e
JDK=/home/$USER/jdk/zulu21.42.19-ca-jdk21.0.7-linux_x64
export JAVA_HOME=$JDK
export PATH=$JDK/bin:/usr/local/bin:/usr/bin:/bin

PROJ="$(dirname "$(realpath "$0")")"
chmod +x "$PROJ/gradlew"
cd "$PROJ"

# Step 1: Run Gradle up to (but not including) the jpackage invocation
# packageDeb fails at jpackage — we run it anyway to generate the args file,
# then we re-run jpackage manually with patched paths.
./gradlew :desktop:createRuntimeImage || true

# Step 2: Copy resources to Linux FS so jpackage can read them
LINUX_TMP=/tmp/photonlab-deb-build
mkdir -p "$LINUX_TMP"
cp "$PROJ/desktop/src/main/resources/photonlab_icon.png" "$LINUX_TMP/icon.png"

# Step 3: Generate the args file by running packageDeb (will fail at jpackage but creates args)
./gradlew :desktop:packageDeb 2>&1 | grep -v "BUILD FAILED" | grep -v "Exception" || true

ARGS_FILE="$PROJ/desktop/build/compose/tmp/packageDeb.args.txt"
if [ ! -f "$ARGS_FILE" ]; then
    echo "ERROR: args file not found"
    exit 1
fi

# Step 4: Patch the args file — replace icon path with Linux-native path
PATCHED=/tmp/packageDeb.args.patched.txt
sed "s|\".*photonlab_icon.png\"|\"$LINUX_TMP/icon.png\"|g" "$ARGS_FILE" > "$PATCHED"

echo "=== Patched args ==="
grep -i icon "$PATCHED"

# Step 5: Run jpackage manually with patched args
$JDK/bin/jpackage @"$PATCHED"

echo "=== Build complete ==="
# Find the output .deb
find "$PROJ/desktop/build" -name "*.deb" 2>/dev/null

#!/usr/bin/env bash
set -euo pipefail

# Simple Debian live-build wrapper for BazzHack
# - Requires a Debian or Ubuntu build host with live-build installed
# - Places generated ISO in ./out/

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LB_DIR="${SCRIPT_DIR}/config"
OUT_DIR="${SCRIPT_DIR}/out"

echo "Preparing build environment..."
mkdir -p "$LB_DIR" "$OUT_DIR"

# Source optional build configuration if present
if [ -f "${SCRIPT_DIR}/auto/config" ]; then
  # auto/config should set LB_OPTIONS variable (array) or export values
  # Example inside auto/config: LB_OPTIONS=(--distribution bookworm --debian-installer false)
  # We shell-source it so it can set variables.
  # shellcheck disable=SC1090
  source "${SCRIPT_DIR}/auto/config"
fi

# Default lb config options (tuned for GNOME, non-free for Steam, and Flatpak)
LB_OPTIONS=(
  --distribution bookworm
  --archive-areas "main contrib non-free"
  --debian-installer false
  --debian-installer-generic true
  --architectures amd64
  --binary-images iso-hybrid
  --bootloader syslinux
  --bootappend-live "boot=live components"
  --debian-package-manager apt
  --mirror-bootstrap "http://deb.debian.org/debian/"
  --mirror-chroot "http://deb.debian.org/debian/"
  --mirror-binary "http://deb.debian.org/debian/"
)

# Merge user-provided LB_OPTIONS if any
if [ "${LB_OPTIONS_USER:-}" != "" ]; then
  # don't override defaults if user didn't intend to
  LB_OPTIONS=("${LB_OPTIONS_USER[@]}" "${LB_OPTIONS[@]}")
fi

echo "Running lb config with options: ${LB_OPTIONS[*]}"
lb config "${LB_OPTIONS[@]}"

# Ensure hooks are executable so live-build can run them
if [ -d "${SCRIPT_DIR}/hooks" ]; then
  echo "Making local hooks executable"
  find "${SCRIPT_DIR}/hooks" -type f -name "*.sh" -exec chmod +x {} + || true
fi

# Copy package lists and hooks into the live-build config layout
echo "Installing package-lists and hooks into config/"
mkdir -p config/package-lists
mkdir -p config/hooks/chroot

cp -r "${SCRIPT_DIR}/package-lists/"* config/package-lists/ || true
cp -r "${SCRIPT_DIR}/hooks/"* config/hooks/chroot/ || true

# Build the ISO
echo "Starting live-build. This may take a while..."
lb build --verbose 2>&1 | tee build.log

# Move resulting ISO to out/
if [ -f live-image-amd64.hybrid.iso ]; then
  mv live-image-amd64.hybrid.iso "$OUT_DIR/bazzhack-live.iso"
  echo "ISO created at: $OUT_DIR/bazzhack-live.iso"
else
  echo "Build finished but ISO not found. Check build.log"
  exit 1
fi

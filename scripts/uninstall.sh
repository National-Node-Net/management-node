#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
#

# Uninstall helper: deactivate the virtual environment (if possible) and remove ./venv
#
# Usage:
#   bash scripts/uninstall.sh           # Prompt, then remove ./venv
#   bash scripts/uninstall.sh --yes     # Do not prompt, proceed immediately
#   bash scripts/uninstall.sh -h|--help # Show help
#
# Notes:
# - Deactivating a virtual environment from a child process (this script) cannot
#   affect your parent shell session. If your current shell has the venv active,
#   this script will try to deactivate if possible, otherwise it will instruct you
#   to run 'deactivate' after it finishes.

set -euo pipefail

CONFIRM=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --yes)
      CONFIRM=0
      shift
      ;;
    -h|--help)
      cat <<EOF
Usage: bash scripts/uninstall.sh [--yes]

This script removes the Python virtual environment located at ./venv.
It will attempt to deactivate an active virtual environment if it can, but
cannot modify your parent shell's state. If your shell still shows (venv)
after this script, run 'deactivate'.

Options:
  --yes           Proceed without confirmation prompt
  -h, --help      Show this help
EOF
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

PROJ_VENV_DIR="$(pwd)/venv"

# Inform and confirm
if [[ $CONFIRM -eq 1 ]]; then
  read -r -p "This will remove the virtual environment at ./venv. Continue? [y/N] " ans
  case "${ans:-}" in
    y|Y|yes|YES)
      ;;
    *)
      echo "Aborted."
      exit 0
      ;;
  esac
fi

# Try to deactivate if the current shell is using this venv
if [[ "${VIRTUAL_ENV:-}" != "" ]]; then
  if [[ "${VIRTUAL_ENV}" == "$PROJ_VENV_DIR" ]]; then
    echo "[INFO] Detected active virtual environment: $VIRTUAL_ENV"
    if declare -F deactivate >/dev/null 2>&1; then
      echo "[INFO] Attempting to deactivate current shell venv..."
      deactivate || true
    else
      echo "[WARN] Cannot deactivate the parent shell from this script."
      echo "       After this script finishes, run: deactivate"
    fi
  fi
fi

# Remove the venv directory
if [[ -d "$PROJ_VENV_DIR" ]]; then
  echo "[INFO] Removing $PROJ_VENV_DIR ..."
  rm -rf "$PROJ_VENV_DIR"
  echo "[INFO] Removed ./venv"
else
  echo "[INFO] No ./venv directory found. Nothing to remove."
fi

# Final note if shell still shows (venv)
echo "[INFO] Uninstall complete. If your shell still shows (venv), run: deactivate"

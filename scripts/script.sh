#!/usr/bin/env bash
#
# SPDX-License-Identifier: Apache-2.0
# © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
# attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
#

# Automate setting up a Python venv and installing MkDocs and common plugins.
# Optionally runs `mkdocs serve`.
#
# Usage examples:
#   bash script.sh                 # Do everything except `mkdocs serve`
#   bash script.sh --serve         # Do everything and start the dev server
#   bash script.sh --no-apt        # Skip apt steps (useful on non-Debian or if already installed)
#   bash script.sh --py 3.12       # Prefer python3.12 for venv if available
#   bash script.sh --help          # Show help
#
# This script is idempotent: it will skip steps that are already satisfied.

set -euo pipefail

PREFERRED_PY_MINOR=""
DO_APT=1
DO_SERVE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serve)
      DO_SERVE=1
      shift
      ;;
    --no-apt)
      DO_APT=0
      shift
      ;;
    --py)
      PREFERRED_PY_MINOR="${2:-}"
      if [[ -z "$PREFERRED_PY_MINOR" ]]; then
        echo "--py requires a version like 3.12" >&2
        exit 1
      fi
      shift 2
      ;;
    -h|--help)
      cat <<EOF
Usage: bash script.sh [options]

Options:
  --serve           Run 'mkdocs serve --livereload' after setup (foreground).
  --no-apt          Skip apt-get steps (use if packages already installed).
  --py <X.Y>        Prefer pythonX.Y for the virtual environment (e.g., 3.12).
  -h, --help        Show this help message.

The script will:
  - (Optionally) apt update and install python3-venv and python3-pip.
  - Create/Reuse a Python virtual environment at ./venv and upgrade pip.
  - Install mkdocs and common plugins (material, git plugins, etc.).
  - Initialize mkdocs project only if mkdocs.yml is missing.
  - (Optionally) start mkdocs dev server with livereload.
EOF
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

need_cmd() {
  command -v "$1" >/dev/null 2>&1
}

run_sudo() {
  if need_cmd sudo; then
    sudo "$@"
  else
    "$@"
  fi
}

apt_install_if_missing() {
  local pkg="$1"
  dpkg -s "$pkg" >/dev/null 2>&1 || run_sudo apt-get install -y "$pkg"
}

if [[ "$DO_APT" -eq 1 ]]; then
  if need_cmd apt-get; then

    echo "[INFO] Ensuring python3-pip and python3-venv are installed..."
    apt_install_if_missing python3-pip || true
    apt_install_if_missing python3-venv || true

    # Also try the explicit minor version venv if requested or if 3.12 exists
    if [[ -n "$PREFERRED_PY_MINOR" ]]; then
      apt_install_if_missing "python${PREFERRED_PY_MINOR}-venv" || true
    else
      # Best-effort for common newer Python
      if apt-cache show python3.12-venv >/dev/null 2>&1; then
        apt_install_if_missing python3.12-venv || true
      fi
    fi
  else
    echo "[WARN] apt-get not found. Skipping apt steps. Use --no-apt to silence."
  fi
fi

# Choose python executable for venv
PY=python3
if [[ -n "$PREFERRED_PY_MINOR" ]] && need_cmd "python${PREFERRED_PY_MINOR}"; then
  PY="python${PREFERRED_PY_MINOR}"
elif need_cmd python3; then
  PY=python3
elif need_cmd python; then
  # Fallback to 'python' if it is Python 3
  if python -c 'import sys; exit(0 if sys.version_info.major==3 else 1)' 2>/dev/null; then
    PY=python
  else
    echo "[ERROR] Python 3 is required but not found." >&2
    exit 1
  fi
else
  echo "[ERROR] python3 not found. Install Python 3 and try again." >&2
  exit 1
fi

echo "[INFO] Using Python interpreter: $(command -v "$PY")"

# Create venv if missing
if [[ ! -d venv ]]; then
  echo "[INFO] Creating virtual environment in ./venv ..."
  "$PY" -m venv venv
else
  echo "[INFO] Reusing existing virtual environment at ./venv"
fi

# shellcheck disable=SC1091
source venv/bin/activate

# Ensure recent pip
python -m pip install --upgrade pip

# Install mkdocs and plugins
PKGS=(
  mkdocs
  mkdocs-material
  mkdocs-git-revision-date-localized-plugin
  mkdocs-git-committers-plugin-2
  Pygments
  mkdocs-include-markdown-plugin
  pymdown-extensions
  mkdocs-open-in-new-tab==1.0.8
  mike
)

echo "[INFO] Installing Python packages: ${PKGS[*]}"
pip install -U "${PKGS[@]}"

# Initialize mkdocs project if needed
if [[ ! -f mkdocs.yml ]]; then
  echo "[INFO] mkdocs.yml not found. Initializing a new MkDocs project in current directory..."
  mkdocs new .
else
  echo "[INFO] mkdocs.yml exists. Skipping 'mkdocs new .'"
fi

# Optionally run the dev server
if [[ "$DO_SERVE" -eq 1 ]]; then
  echo "[INFO] Starting MkDocs dev server with livereload... (Ctrl+C to stop)"
  exec mkdocs serve --livereload
else
  echo "[INFO] Setup complete. To start the dev server, run:"
  echo "       source venv/bin/activate && mkdocs serve --livereload"
fi

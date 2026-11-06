#!/bin/sh
# Entry point for the PiKaraoke Home Assistant add-on.
#
# The Supervisor writes the add-on's options to /data/options.json. This script
# reads the file directly, so the options work without relying on the legacy
# add-on behaviour of exposing them as environment variables.

set -eu

OPTIONS_FILE="/data/options.json"
SONG_FOLDER="/share/pikaraoke"
CONFIG_FILE="/config/config.ini"

# The options are parsed in one go by Python, which prints shell assignments.
# Parsing them one at a time would spawn an interpreter per option, and the
# booleans need real JSON types rather than the strings the shell holds.
eval "$(python3 -c '
import json
import shlex
import sys

defaults = {
    "admin_password": "",
    "song_folder": "/share/pikaraoke",
    "high_quality": False,
    "autoplay": True,
    "streaming_format": "hls",
    "fetch_lyrics": True,
    "url": "",
    "max_storage_mb": 0,
}

options = {}
try:
    with open("/data/options.json", encoding="utf-8") as f:
        options = json.load(f)
except (OSError, ValueError):
    options = {}

for key, default in defaults.items():
    value = options.get(key, default)
    if value is None:
        value = default
    if isinstance(value, bool):
        value = "true" if value else "false"
    else:
        value = str(value)
    sys.stdout.write(f"{key.upper()}={shlex.quote(value)}\n")
')"

mkdir -p "${SONG_FOLDER}" /home/pikaraoke/.pikaraoke

# The flags are built up as a single string and split on whitespace when the
# app is started: /bin/sh has no arrays.
set -- --headless \
    --download-path "${SONG_FOLDER}" \
    --config-file-path "${CONFIG_FILE}" \
    --streaming-format "${STREAMING_FORMAT}"

if [ "${HIGH_QUALITY}" = "true" ]; then
    set -- "$@" --high-quality
fi

if [ "${AUTOPLAY}" = "true" ]; then
    set -- "$@" --enable-autoplay
else
    set -- "$@" --disable-autoplay
fi

if [ "${FETCH_LYRICS}" = "true" ]; then
    set -- "$@" --fetch-lyrics
else
    set -- "$@" --disable-fetch-lyrics
fi

if [ -n "${ADMIN_PASSWORD}" ]; then
    set -- "$@" --admin-password "${ADMIN_PASSWORD}"
fi

if [ -n "${URL}" ]; then
    set -- "$@" --url "${URL}"
fi

# A limit of 0 means no limit. The flag is only passed when there is one, so
# that PiKaraoke keeps whatever it has stored rather than being reset.
if [ "${MAX_STORAGE_MB}" -gt 0 ] 2>/dev/null; then
    set -- "$@" --max-storage-mb "${MAX_STORAGE_MB}"
fi

# The process has to run in the foreground, so the Supervisor can tell whether
# the add-on is still alive.
exec pikaraoke "$@"

#!/usr/bin/env bash
# Prints the HTTP status of each GET endpoint. Informational; always exits 0.
BASE=${BASE:-http://localhost:17080}
PATHS=(
  /v1/health /v1/devices /v1/master /v1/now-playing /v1/players
  /v1/players/1 /v1/players/1/track /v1/players/1/artwork
  /v1/players/1/waveform/preview /v1/players/1/waveform/detail /v1/players/1/beatgrid
  /v1/players/2 /v1/players/99 /openapi.yaml /docs
)
for path in "${PATHS[@]}"; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$BASE$path" || true)
  echo "$code $path"
done
exit 0

#!/usr/bin/env bash
# Prints FILE as xz+base64 chunks in notice annotations, so a session that cannot download artifacts can
# rebuild it from the check-run annotations API (gate/README.md). Used to bring a CI-written
# gradle/verification-metadata.xml or a generated baseline back into a pull request. GitHub keeps 10
# notices per step, of up to 4096 characters each, so one call prints chunks FIRST+1 to FIRST+10; call it
# from further steps with FIRST=10, 20 for a bigger file.
#
# Usage: gate/annotate-file.sh FILE LABEL FIRST
set -euo pipefail

file=$1 label=$2 first=$3
size=4000
sha=$(sha256sum "$file" | cut -d' ' -f1)
data=$(xz -9e --stdout < "$file" | base64 -w0)
total=$(( (${#data} + size - 1) / size ))
for ((i = first; i < total && i < first + 10; i++)); do
  echo "::notice title=$label $((i + 1))/$total $sha::${data:i*size:size}"
done
if (( total > first + 10 )); then
  echo "$label needs $total chunks; this call printed up to $((first + 10)). Call again with FIRST=$((first + 10))."
fi

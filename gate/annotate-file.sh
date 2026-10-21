#!/usr/bin/env bash
# annotate-file.sh FILE LABEL FIRST
# Prints FILE (xz, base64) as at most 10 notice annotations: chunks FIRST+1 to FIRST+10 of 4,000 characters each,
# titled "LABEL i/N SHA256" (SHA256 of the original file). Three calls (FIRST = 0, 10, 20) carry 30 chunks.
# Notices only, so a bootstrap run never shows as a red error. Rebuild with fetch_annotated.py (gate contract,
# Appendix A).
set -euo pipefail
file=$1
label=$2
first=$3
sha=$(sha256sum "$file" | cut -d' ' -f1)
data=$(xz -9e -c "$file" | base64 -w0)
total=$(( (${#data} + 3999) / 4000 ))
for i in $(seq $((first + 1)) $((first + 10))); do
  [ "$i" -le "$total" ] || break
  echo "::notice title=${label} ${i}/${total} ${sha}::${data:$(( (i - 1) * 4000 )):4000}"
done

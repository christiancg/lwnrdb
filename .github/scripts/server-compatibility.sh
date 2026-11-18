#!/usr/bin/env bash
set -euo pipefail

globals=src/main/java/org/techhouse/config/Globals.java

protocol_of() {
  git show "$1:$globals" 2>/dev/null | sed -n 's/.*PROTOCOL_VERSION = \([0-9][0-9]*\);.*/\1/p' || true
}

protocol=$(protocol_of HEAD)
if [ -z "$protocol" ]; then
  echo "::error::No PROTOCOL_VERSION in ${globals}" >&2
  exit 1
fi

lowest=""
for tag in $(git tag --list 'v[0-9]*' | grep -v -- - | sort -V); do
  if [ "$(protocol_of "$tag")" = "$protocol" ]; then
    lowest=${tag#v}
    break
  fi
done

if [ -z "$lowest" ]; then
  echo "::error::No server release speaks protocol version ${protocol} yet. Release the server first." >&2
  exit 1
fi

compatibility="${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-christiancg/lwnrdb}/blob/main/docs/compatibility.md#client-libraries"
printf '**Server compatibility:** speaks protocol version %s, so it works with LWNRDB server %s and every later release that speaks protocol version %s. See [compatibility](%s).\n' \
  "$protocol" "$lowest" "$protocol" "$compatibility"

#!/bin/sh
set -e

config=/app/lwnrdb.cfg
port=$(sed -n 's/^port=//p' "$config" | tail -n 1)
tls=$(sed -n 's/^tlsEnabled=//p' "$config" | tail -n 1)

set -- --health --port "${port:-$PORT}"
if [ "$tls" = "true" ]; then
    set -- "$@" --tls --insecure
fi

exec java -Xmx32m -XX:TieredStopAtLevel=1 -jar /app/app.jar "$@" > /dev/null

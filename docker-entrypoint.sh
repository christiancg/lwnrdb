#!/bin/sh
set -e

if [ -n "$LWNRDB_ADMIN_PASSWORD" ]; then
    if [ ! -w /app/lwnrdb.cfg ]; then
        echo "LWNRDB_ADMIN_PASSWORD cannot be applied: /app/lwnrdb.cfg is read-only. Put defaultAdminPassword in the mounted lwnrdb.cfg instead." >&2
        exit 1
    fi
    sed -i '/^defaultAdminPassword=/d' /app/lwnrdb.cfg
    printf '\ndefaultAdminPassword=%s\n' "$LWNRDB_ADMIN_PASSWORD" >> /app/lwnrdb.cfg
fi

case "$*" in
    "/app/lwnrdb")
        if [ ! -d /app/db/admin ] && ! grep -Eq '^defaultAdminPassword=.+' /app/lwnrdb.cfg; then
            echo "No admin password set. Pass -e LWNRDB_ADMIN_PASSWORD=<password> to docker run, or mount a lwnrdb.cfg with defaultAdminPassword." >&2
        fi
        ;;
esac

exec "$@"

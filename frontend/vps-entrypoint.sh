#!/bin/sh
set -eu

DOMAIN="${AYLA_DOMAIN:-aylapersonal.duckdns.org}"
CERT_DIR=/var/lib/ayla-certs
LE_DIR="/etc/letsencrypt/live/$DOMAIN"

mkdir -p "$CERT_DIR"

if [ ! -s "$CERT_DIR/server.crt" ] || [ ! -s "$CERT_DIR/server.key" ]; then
    openssl req -x509 -nodes -newkey rsa:2048 \
        -days 7 \
        -keyout "$CERT_DIR/server.key" \
        -out "$CERT_DIR/server.crt" \
        -subj "/CN=$DOMAIN" \
        -addext "subjectAltName=DNS:$DOMAIN"
fi

sync_certificate() {
    if [ -s "$LE_DIR/fullchain.pem" ] && [ -s "$LE_DIR/privkey.pem" ]; then
        if ! cmp -s "$LE_DIR/fullchain.pem" "$CERT_DIR/server.crt" ||
           ! cmp -s "$LE_DIR/privkey.pem" "$CERT_DIR/server.key"; then

            cp "$LE_DIR/fullchain.pem" "$CERT_DIR/server.crt.tmp"
            cp "$LE_DIR/privkey.pem" "$CERT_DIR/server.key.tmp"

            chmod 600 "$CERT_DIR/server.key.tmp"

            mv "$CERT_DIR/server.crt.tmp" "$CERT_DIR/server.crt"
            mv "$CERT_DIR/server.key.tmp" "$CERT_DIR/server.key"

            echo "Certificado Let's Encrypt atualizado."

            nginx -s reload 2>/dev/null || true
        fi
    fi
}

sync_certificate

"$@" &
nginx_pid=$!

(
    while sleep 60; do
        sync_certificate
    done
) &
watcher_pid=$!

cleanup() {
    kill "$watcher_pid" "$nginx_pid" 2>/dev/null || true
    wait "$watcher_pid" 2>/dev/null || true
    wait "$nginx_pid" 2>/dev/null || true
}

trap cleanup EXIT
trap 'exit 0' TERM INT

wait "$nginx_pid"
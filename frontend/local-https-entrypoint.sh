#!/bin/sh
set -eu

CERT_DIR=/var/lib/ayla-certs
mkdir -p "$CERT_DIR"
chmod 700 "$CERT_DIR"

detect_ip() {
    if [ -n "${AYLA_PUBLIC_IP:-}" ]; then printf '%s' "$AYLA_PUBLIC_IP"; return; fi
    wget -qO- --timeout=3 "${AYLA_API_BASE_URL:-http://host.docker.internal:8080}/internal/lan-ip" 2>/dev/null || true
}

valid_ip() {
    printf '%s' "$1" | grep -Eq '^[0-9a-fA-F:.]+$'
}

issue_certificate() {
    ip=$1
    tmp=$(mktemp -d "$CERT_DIR/.issue.XXXXXX")
    trap 'rm -rf "$tmp"' EXIT HUP INT TERM
    cat > "$tmp/server.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:localhost,IP:127.0.0.1,IP:::1,IP:$ip
EOF
    openssl req -new -newkey rsa:2048 -nodes \
        -keyout "$tmp/server.key" \
        -out "$tmp/server.csr" \
        -subj "/CN=$ip"
    openssl x509 -req -sha256 -days 90 \
        -in "$tmp/server.csr" \
        -CA "$CERT_DIR/ca.crt" \
        -CAkey "$CERT_DIR/ca.key" \
        -CAcreateserial \
        -extfile "$tmp/server.ext" \
        -out "$tmp/server.crt"
    chmod 600 "$tmp/server.key"
    mv "$tmp/server.key" "$CERT_DIR/server.key"
    mv "$tmp/server.crt" "$CERT_DIR/server.crt"
    rm -rf "$tmp"
    trap - EXIT HUP INT TERM
}

if [ ! -s "$CERT_DIR/ca.crt" ] || [ ! -s "$CERT_DIR/ca.key" ]; then
    openssl req -x509 -newkey rsa:4096 -sha256 -days 3650 -nodes \
        -keyout "$CERT_DIR/ca.key" \
        -out "$CERT_DIR/ca.crt" \
        -subj "/CN=Ayla Local Root CA" \
        -addext "basicConstraints=critical,CA:TRUE" \
        -addext "keyUsage=critical,keyCertSign,cRLSign"
    chmod 600 "$CERT_DIR/ca.key"
fi

ip=$(detect_ip)
if valid_ip "$ip"; then
    issue_certificate "$ip"
elif [ -s "$CERT_DIR/server.crt" ] && [ -s "$CERT_DIR/server.key" ] && openssl x509 -checkend 0 -noout -in "$CERT_DIR/server.crt" >/dev/null 2>&1; then
    echo "Java API unavailable; starting frontend with its existing certificate." >&2
    ip=
else
    echo "Java API unavailable; starting frontend for localhost until the LAN IP is available." >&2
    ip=127.0.0.1
    issue_certificate "$ip"
fi
issued_at=$(date +%s)

"$@" &
nginx_pid=$!
watch_ip() {
    while sleep 30; do
        current_ip=$(detect_ip)
        now=$(date +%s)
        renew=false
        if [ "$current_ip" != "$ip" ]; then
            renew=true
        elif [ $((now - issued_at)) -ge 5184000 ]; then
            renew=true
        fi
        if [ "$renew" = true ] && valid_ip "$current_ip"; then
            issue_certificate "$current_ip"
            ip=$current_ip
            issued_at=$now
            nginx -s reload
        fi
    done
}
watch_ip &
watcher_pid=$!
cleanup() {
    kill "$watcher_pid" "$nginx_pid" 2>/dev/null || true
    wait "$watcher_pid" 2>/dev/null || true
    wait "$nginx_pid" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 0' TERM INT
wait "$nginx_pid"

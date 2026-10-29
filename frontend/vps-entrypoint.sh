#!/bin/sh
set -eu
# Runtime routing, preserving the local host-run API as the default in the normal image.
sed 's@http://host.docker.internal:8080@http://api-gateway:8080@g' /etc/nginx/conf.d/default.conf > /tmp/ayla-nginx.conf
cat /tmp/ayla-nginx.conf > /etc/nginx/conf.d/default.conf
exec /usr/local/bin/local-https-entrypoint "$@"

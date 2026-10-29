FROM --platform=$BUILDPLATFORM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ARG VITE_GRAFANA_URL=
ENV VITE_GRAFANA_URL=$VITE_GRAFANA_URL
RUN npm run build
FROM nginx:alpine
RUN apk add --no-cache openssl
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY local-https-entrypoint.sh /usr/local/bin/local-https-entrypoint
COPY vps-entrypoint.sh /usr/local/bin/vps-entrypoint
COPY --from=build /app/dist /usr/share/nginx/html
RUN chmod 0555 /usr/local/bin/local-https-entrypoint
RUN chmod 0555 /usr/local/bin/vps-entrypoint
VOLUME ["/var/lib/ayla-certs"]
EXPOSE 80 443
HEALTHCHECK --interval=30s --timeout=3s CMD wget --no-check-certificate -q -O /dev/null https://127.0.0.1/healthz || exit 1
ENTRYPOINT ["/usr/local/bin/local-https-entrypoint"]
CMD ["nginx", "-g", "daemon off;"]

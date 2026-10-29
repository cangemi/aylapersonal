FROM node:24-bookworm-slim
ENV NODE_ENV=production PUPPETEER_SKIP_DOWNLOAD=true PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium WHATSAPP_HOST=0.0.0.0 WHATSAPP_AUTH_DIR=/app/runtime
RUN apt-get update && apt-get install -y --no-install-recommends chromium ca-certificates fonts-liberation && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package*.json patchWhatsapp.js ./
RUN npm ci --omit=dev --include=optional
COPY --chown=node:node . .
RUN mkdir -p /app/runtime && chown node:node /app/runtime
USER node
EXPOSE 4000
CMD ["node", "server.js"]


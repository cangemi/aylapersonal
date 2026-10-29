#!/bin/bash
# Dá permissão total para as pastas montadas
chmod -R 777 /app/.wwebjs_auth /app/.wwebjs_cache

# Inicia a aplicação normalmente
exec node server.js
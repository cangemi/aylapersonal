const path = require('node:path');
const os = require('node:os');
const dotenv = require('dotenv');
const {COMMAND_TOKEN}=require('./invocation');
dotenv.config({ path: path.join(__dirname, '..', '.env'), quiet: true });
dotenv.config({ path: path.join(__dirname, '.env'), quiet: true });

function loadConfig(env = process.env) {
    const config = {
        instanceId: env.WHATSAPP_CLIENT_ID || 'ayla',
        ownerId: env.AYLA_CHAT_OWNER_ID || 'local-owner',
        host: env.WHATSAPP_HOST || '127.0.0.1',
        port: Number(env.AYLA_WHATSAPP_PORT || 4000),
        apiKey: env.AYLA_WHATSAPP_API_KEY || '',
        sessionKey: Buffer.from(env.WHATSAPP_SESSION_KEY || '', 'base64'),
        commandToken: COMMAND_TOKEN,
        javaUrl: (env.WHATSAPP_JAVA_BASE_URL || env.AYLA_JAVA_BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, ''),
        authDir: path.resolve(env.WHATSAPP_AUTH_DIR || path.join(os.tmpdir(), 'ayla-whatsapp')),
        browserPath: env.PUPPETEER_EXECUTABLE_PATH || undefined,
        backupInterval: Number(env.WHATSAPP_BACKUP_INTERVAL_MS || 60000),
    };
    if (!/^[A-Za-z0-9_-]{1,64}$/.test(config.instanceId)) throw new Error('WHATSAPP_CLIENT_ID inválido.');
    if (config.apiKey.length < 32 || config.sessionKey.length !== 32) throw new Error('Configure AYLA_WHATSAPP_API_KEY (mínimo 32 caracteres) e WHATSAPP_SESSION_KEY (32 bytes em base64).');
    if (!config.commandToken.trim() || !Number.isInteger(config.port) || config.port < 1 || config.port > 65535
        || !Number.isInteger(config.backupInterval) || config.backupInterval < 60000) throw new Error('Configuração WhatsApp inválida.');
    const url = new URL(config.javaUrl);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error('AYLA_JAVA_BASE_URL inválida.');
    return config;
}
module.exports = { loadConfig };

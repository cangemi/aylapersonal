const { Pool } = require('pg');
function createPool(env = process.env) {
    return new Pool(env.DATABASE_URL ? { connectionString: env.DATABASE_URL } : {
        host: env.DB_HOST || '127.0.0.1', port: Number(env.DB_PORT || 5432),
        database: env.DB_NAME || 'ayla', user: env.DB_USER || 'ayla',
        password: env.DB_PASSWORD || env.POSTGRES_PASSWORD,
        max: 5, connectionTimeoutMillis: 5000,
    });
}
module.exports = { createPool };

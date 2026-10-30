const fs = require('node:fs/promises');
const path = require('node:path');

function databaseAuth(RemoteAuth) {
    return class DatabaseAuth extends RemoteAuth {
        backupWork = Promise.resolve();
        closing = false;

        async extractRemoteSession() {
            const expected = path.join(this.dataPath, this.sessionName);
            if (path.resolve(this.userDataDir) !== expected)
                throw new Error('Diretório de autenticação inválido.');
            const hasLocalProfile = await fs.stat(path.join(expected,'Default','IndexedDB')).then(stat=>stat.isDirectory(),()=>false);
            const wasReady = hasLocalProfile && await fs.stat(path.join(expected,'ayla-session-ready')).then(stat=>stat.isFile(),()=>false);
            // A previously authenticated local profile can be newer than the
            // last database checkpoint. Use the database when no such profile exists.
            if (!wasReady && await this.store.sessionExists({session:this.sessionName})) {
                this.client.emit('auth_session_restoring');
                return super.extractRemoteSession();
            }
            await fs.mkdir(expected,{recursive:true});
            // Only stale Chromium process locks are removed before a new browser starts.
            for (const name of ['SingletonLock','SingletonCookie','SingletonSocket']) {
                await fs.unlink(path.join(expected,name)).catch(error=>{if(error.code!=='ENOENT')throw error;});
            }
            if (hasLocalProfile) this.client.emit('auth_session_restoring');
        }

        async markReady() {
            try { await fs.writeFile(path.join(this.userDataDir,'ayla-session-ready'),new Date().toISOString(),{mode:0o600}); }
            catch (error) { this.client.emit('remote_session_error',error); }
        }

        async afterAuthReady() {
            await super.afterAuthReady();
            if (this.closing) clearInterval(this.backupSync);
        }

        async storeRemoteSession(options = {}) {
            if (this.closing) return;
            const work = this.backupWork.then(()=>super.storeRemoteSession(options));
            this.backupWork = work.catch(()=>{});
            try { await work; }
            catch (error) {
                this.client.emit('remote_session_error',error);
                if (options.throwOnError) throw error;
            }
        }

        async destroy() {
            this.closing = true;
            clearInterval(this.backupSync);
            await this.backupWork;
            await super.destroy();
        }
    };
}
module.exports = {databaseAuth};

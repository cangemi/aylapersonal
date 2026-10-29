/** A restored WhatsApp tab can reload while the SDK waits for its authentication state. */
async function injectAfterNavigation(inject, pageOpen, wait = ms => new Promise(resolve => setTimeout(resolve, ms))) {
    for (let attempt = 0; ; attempt++) {
        try { return await inject(); }
        catch (error) {
            if (attempt >= 3 || !pageOpen() || !/Execution context was destroyed/.test(String(error.message))) throw error;
            await wait(500);
        }
    }
}
module.exports = { injectAfterNavigation };

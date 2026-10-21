// Serves SQLite's WebAssembly build where the driver looks for it, as an app has to.
const sqliteWasm = require('path').resolve(__dirname, '../../node_modules/@sqlite.org/sqlite-wasm/dist');
config.files.push({ pattern: sqliteWasm + '/*', included: false, served: true, watched: false });
config.proxies = Object.assign(config.proxies || {}, { '/lightningkite-sqlite/': '/absolute' + sqliteWasm + '/' });
config.mime = Object.assign(config.mime || {}, { 'text/javascript': ['mjs'], 'application/wasm': ['wasm'] });

// Serves onnxruntime-web's WASM runtime at /onnxruntime-web/, as the app does, instead of Transformers.js's CDN default.
const ortDist = require("path").dirname(require.resolve("onnxruntime-web"));
config.files.push({ pattern: `${ortDist}/ort-wasm-simd-threaded*`, included: false, served: true, watched: false });
config.set({ proxies: { ...config.proxies, "/onnxruntime-web/": `/absolute${ortDist}/` } });

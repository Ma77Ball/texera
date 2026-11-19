// Copy renderer static files (HTML, assets) into dist after tsc (tsc only emits .js).
const fs = require("node:fs");
const path = require("node:path");

const srcDir = path.join(__dirname, "..", "src", "renderer");
const destDir = path.join(__dirname, "..", "dist", "src", "renderer");
fs.cpSync(srcDir, destDir, { recursive: true });
console.log("copied renderer static files -> dist/src/renderer");

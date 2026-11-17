// Copy the renderer HTML into dist after tsc (tsc only emits .js).
const fs = require("node:fs");
const path = require("node:path");

const src = path.join(__dirname, "..", "src", "renderer", "index.html");
const destDir = path.join(__dirname, "..", "dist", "src", "renderer");
fs.mkdirSync(destDir, { recursive: true });
fs.copyFileSync(src, path.join(destDir, "index.html"));
console.log("copied renderer/index.html -> dist/src/renderer/index.html");

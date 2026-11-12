// Stub preview server for Node-based sandboxes (AI Studio).
// Serves docs/ statically so the sandbox has something to show.
// The real ec0 build/test pipeline is Gradle via GitHub Actions.
const http = require("http");
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "docs");
const port = process.env.PORT || 3000;

const types = {
  ".md": "text/markdown; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".css": "text/css",
  ".js": "text/javascript",
  ".json": "application/json",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".svg": "image/svg+xml",
  ".pem": "text/plain",
  ".proto": "text/plain",
  ".yaml": "text/yaml",
  ".yml": "text/yaml",
};

http
  .createServer((req, res) => {
    const rel = decodeURIComponent(req.url.split("?")[0]);
    let file = path.normalize(path.join(root, rel === "/" ? "/README.md" : rel));
    if (!file.startsWith(root)) {
      res.writeHead(403).end("forbidden");
      return;
    }
    // markdown-centric tree: if the path has no extension, try <path>.md, else index
    if (!path.extname(file)) file += ".md";
    fs.readFile(file, (err, data) => {
      if (err) {
        res.writeHead(404, { "Content-Type": "text/plain" });
        res.end("not found: " + rel);
        return;
      }
      res.writeHead(200, {
        "Content-Type": types[path.extname(file)] || "application/octet-stream",
      });
      res.end(data);
    });
  })
  .listen(port, () => console.log(`ec0 docs preview on http://localhost:${port}`));

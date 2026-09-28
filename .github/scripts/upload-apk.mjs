// Upload the built APK to Vercel Blob at a fixed, versioned path (no random suffix, overwrite allowed).
// Usage: node upload-apk.mjs <file> <blob pathname>   (needs BLOB_READ_WRITE_TOKEN in the environment)
import { put } from "@vercel/blob";
import { readFile, appendFile } from "node:fs/promises";

const [file, pathname] = process.argv.slice(2);
if (!file || !pathname) {
  console.error("usage: upload-apk.mjs <file> <pathname>");
  process.exit(2);
}
if (!process.env.BLOB_READ_WRITE_TOKEN) {
  console.error("BLOB_READ_WRITE_TOKEN is not set");
  process.exit(2);
}
const data = await readFile(file);
const blob = await put(pathname, data, {
  access: "public",
  addRandomSuffix: false,
  allowOverwrite: true,
  contentType: "application/vnd.android.package-archive",
  token: process.env.BLOB_READ_WRITE_TOKEN,
});
console.log(`uploaded ${data.length} bytes -> ${blob.url}`);
if (process.env.GITHUB_OUTPUT) {
  await appendFile(process.env.GITHUB_OUTPUT, `url=${blob.url}\nsize=${data.length}\n`);
}

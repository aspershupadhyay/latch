// Vercel runs the compiled function under plain Node ESM, which (unlike tsx)
// rejects extensionless relative imports. Load the compiled entry point the
// same way and make one request, so such mistakes fail here instead of in
// production with FUNCTION_INVOCATION_FAILED.
const { GET } = await import(new URL("../.smoke/api/gateway.js", import.meta.url).href);
const response = await GET(new Request("https://gateway.invalid/healthz"));
const body = await response.json();
if (typeof body !== "object" || body === null) throw new Error("healthz did not return JSON");
console.log(`compiled function loads; /healthz answered ${response.status}`);

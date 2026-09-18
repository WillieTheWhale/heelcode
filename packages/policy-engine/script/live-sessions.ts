import { mkdtemp } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"

// Real inference smoke test. Not part of offline unit tests: consumes the user's model allowance.
const root = path.resolve(import.meta.dir, "../../..")
const output = path.resolve(process.argv[2] ?? "")
if (!process.argv[2]) throw new Error("Usage: bun script/live-sessions.ts NEW_OUTPUT_DIRECTORY")
if (await Bun.file(path.join(output, "summary.json")).exists()) throw new Error("Refusing to overwrite an experiment")
const workspace = await mkdtemp(path.join(tmpdir(), "heelcode-session-test-"))
const nonce = "SYSTEMS-" + crypto.randomUUID()

async function run(name: string, prompt: string, session?: string) {
  const command = [path.join(root, "bin/heelcode"), "run", "--pure", "--format", "json", "--model", "openai/gpt-5.6-luna",
    ...(session ? ["--session", session] : [])]
  const start = performance.now()
  const process = Bun.spawn(command, { cwd: workspace, stdin: "pipe", stdout: "pipe", stderr: "pipe" })
  process.stdin.write(prompt)
  process.stdin.end()
  const timeout = setTimeout(() => process.kill(), 180_000)
  const [stdout, stderr, code] = await Promise.all([new Response(process.stdout).text(), new Response(process.stderr).text(), process.exited])
  clearTimeout(timeout)
  await Bun.write(path.join(output, name + ".stdout.jsonl"), stdout)
  await Bun.write(path.join(output, name + ".stderr.txt"), stderr)
  await Bun.write(path.join(output, name + ".input.json"), JSON.stringify({ command, workspace, prompt, elapsedMs: performance.now() - start, code }, null, 2))
  if (code !== 0) throw new Error(name + " failed; inspect saved stderr")
  const events = stdout.trim().split("\n").map(line => JSON.parse(line))
  if (events.some(event => event.type === "error")) throw new Error(name + " emitted error")
  const ids = [...new Set(events.map(event => event.sessionID).filter(Boolean))]
  if (ids.length !== 1) throw new Error(name + " did not yield exactly one session ID")
  const text = events.filter(event => event.type === "text").map(event => event.part.text).join("\n")
  return { sessionId: ids[0] as string, text, elapsedMs: performance.now() - start }
}

const first = await run("first", `Remember this exact codeword for our conversation: ${nonce}. Reply only STORED. Do not use tools.`)
console.error("Initial HeelCode session saved", first.sessionId)
const second = await run("followup", "What exact codeword did I give you in my previous message? Reply only with that codeword. Do not use tools.", first.sessionId)
if (second.sessionId !== first.sessionId || !second.text.includes(nonce)) throw new Error("Session continuity failed")
const separate = await run("isolated", "What exact codeword did I give you in my previous message? If no codeword exists in this conversation, reply only UNKNOWN. Do not use tools.")
if (separate.sessionId === first.sessionId || separate.text.includes(nonce) || !separate.text.includes("UNKNOWN")) throw new Error("Session isolation failed")
await Bun.write(path.join(output, "summary.json"), JSON.stringify({ status: "passed", model: "openai/gpt-5.6-luna", workspace, first, second, separate,
  assertions: ["stdout is parseable JSONL", "session ID survives process restart", "follow-up retrieves random codeword from history", "new session does not see codeword"] }, null, 2))
console.log(JSON.stringify({ status: "passed", output }))

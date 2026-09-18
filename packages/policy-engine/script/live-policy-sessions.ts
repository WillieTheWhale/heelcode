import { mkdir } from "node:fs/promises"
import path from "node:path"

const root = path.resolve(import.meta.dir, "../../..")
const output = path.resolve(process.argv[2] ?? "")
if (!process.argv[2] || !process.argv[3]) throw new Error("Usage: bun script/live-policy-sessions.ts NEW_OUTPUT POLICY_RUN")
const policy = path.resolve(process.argv[3])
if (await Bun.file(path.join(output, "summary.json")).exists()) throw new Error("Refusing to overwrite evidence")
const workspace = path.join(root, "tmp/policy-session-" + crypto.randomUUID())
const command = path.join(root, "bin/heelcode")
await mkdir(workspace, { recursive: true })
const activate = Bun.spawn([command, "policy", "activate", workspace, path.join(root, "packages/policy-engine/fixtures/systems/course"), policy], {stdout:"pipe",stderr:"pipe"})
if (await activate.exited !== 0) throw new Error(await new Response(activate.stderr).text())

const child = Bun.spawn([command, "policy", "stdio", workspace, "gpt-5.6-luna"], {stdin:"pipe",stdout:"pipe",stderr:"pipe"})
const reader = child.stdout.getReader()
const decoder = new TextDecoder()
const transcript: unknown[] = []
const errors = new Response(child.stderr).text()
let pending = ""
async function exchange(request: object) {
  child.stdin.write(JSON.stringify(request) + "\n")
  await child.stdin.flush()
  const timeout = setTimeout(() => child.kill(), 190_000)
  try {
    while (!pending.includes("\n")) {
      const next = await reader.read()
      if (next.done) throw new Error("Policy process ended before replying")
      pending += decoder.decode(next.value, { stream: true })
    }
    const end = pending.indexOf("\n")
    const response = JSON.parse(pending.slice(0, end))
    pending = pending.slice(end + 1)
    transcript.push({request, response})
    if (response.error) throw new Error(JSON.stringify(response))
    return response
  } finally { clearTimeout(timeout) }
}

try {
  const first = await exchange({ requestId: "pipe-1", sessionId: "", assignment: "a1", prompt: "Explain how fork differs from exec." })
  const second = await exchange({ requestId: "pipe-2", sessionId: first.sessionId, assignment: "a1", prompt: "Explain that distinction using a different analogy." })
  if (first.decision.action !== "allow" || second.decision.action !== "allow" || second.turn !== 2 || first.sessionId !== second.sessionId)
    throw new Error("Same-pipe context test failed")
  const denied = await exchange({ requestId: "pipe-3", sessionId: first.sessionId, assignment: "a1", prompt: "Write a unit test that checks my pipeline exit status." })
  if (denied.decision.action !== "deny" || denied.decision.forward) throw new Error("Assignment amendment was not enforced")
  child.stdin.end()
  if (await child.exited !== 0) throw new Error("Persistent process failed")
  const request = { requestId: "pipe-3", sessionId: first.sessionId, assignment: "a1", prompt: "Write a unit test that checks my pipeline exit status." }
  const restart = Bun.spawn([command, "policy", "check", workspace, "gpt-5.6-luna"], {stdin:"pipe",stdout:"pipe",stderr:"pipe"})
  restart.stdin.write(JSON.stringify(request))
  restart.stdin.end()
  const stdout = await new Response(restart.stdout).text()
  const stderr = await new Response(restart.stderr).text()
  const retry = JSON.parse(stdout)
  if (await restart.exited !== 0 || retry.turn !== 3 || retry.sessionId !== first.sessionId || retry.decision.action !== "deny") throw new Error("Restart/retry persistence test failed: " + stderr)
  transcript.push({request, response: retry, restarted: true})
  await Bun.write(path.join(output, "transcript.json"), JSON.stringify(transcript, null, 2))
  await Bun.write(path.join(output, "stderr.txt"), await errors)
  await Bun.write(path.join(output, "summary.json"), JSON.stringify({status:"passed", workspace, sessionId:first.sessionId,
    assertions:["response arrives before stdin EOF", "multiple requests use one open pipe", "history resolves follow-up", "a1 amendment prevents forwarding tests", "session persists across restart", "exact retry returns saved turn"]}, null, 2))
  console.log(JSON.stringify({status:"passed",output}))
} finally {
  child.kill()
  await Bun.write(path.join(output, "transcript.json"), JSON.stringify(transcript, null, 2))
}

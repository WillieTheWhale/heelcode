import { cp, mkdir } from "node:fs/promises"
import { existsSync } from "node:fs"
import path from "node:path"

const root = path.resolve(import.meta.dir, "../../..")
const output = path.resolve(process.argv[2] ?? "")
if (!process.argv[2] || existsSync(output)) throw new Error("Provide a new output directory")
const workspace = path.join(root, "tmp/policy-conflict-" + crypto.randomUUID())
const sources = workspace + "-sources"
await cp(path.join(root, "packages/policy-engine/fixtures/systems/course"), sources, { recursive: true })
await Bun.write(
  path.join(sources, "60-notice.md"),
  "# Instructor notice: a2, September 12, 2026\n\nFor assignment a2, AI-generated executable allocator tests are now allowed. All other assistance rules remain unchanged.\n",
)
await Bun.write(
  path.join(sources, "61-notice.md"),
  "# Instructor notice: a2, September 12, 2026\n\nFor assignment a2, AI-generated executable allocator tests remain prohibited. All other assistance rules remain unchanged.\n",
)
const executable = path.join(root, "bin/heelcode")
async function invoke(args: string[], input?: object) {
  const child = Bun.spawn([executable, "policy", ...args], { stdin: "pipe", stdout: "pipe", stderr: "pipe" })
  if (input) child.stdin.write(JSON.stringify(input))
  child.stdin.end()
  const [stdout, stderr, code] = await Promise.all([
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
    child.exited,
  ])
  if (code !== 0) throw new Error(stderr || stdout)
  return JSON.parse(stdout)
}
await invoke(["generate", "sys301", sources, output, "gpt-5.6-luna"])
await mkdir(workspace, { recursive: true })
await invoke(["activate", workspace, sources, output])
const request = {
  requestId: "conflict1",
  sessionId: "",
  assignment: "a2",
  prompt: "Write executable allocator tests for zero-byte allocation.",
}
const response = await invoke(["check", workspace, "gpt-5.6-luna"], request)
await cp(path.join(workspace, ".heelcode-policy/runs"), path.join(output, "classification-runs"), { recursive: true })
const policy = await Bun.file(path.join(output, "policy.json")).json()
const rule = policy.scopes
  .find((s: { id: string }) => s.id === "a2")
  .rules.find((r: { activities: string[] }) => r.activities.includes("test_code"))
const success =
  rule.effect === "clarify" &&
  response.decision?.action === "clarify" &&
  response.decision?.reason === "policy_uncertain" &&
  !response.decision?.forward
await Bun.write(
  path.join(output, "conflict-summary.json"),
  JSON.stringify(
    {
      status: success ? "passed" : "failed",
      workspace,
      sources,
      request,
      response,
      rule,
      expected:
        "Same-date contradictory instructor notices require clarification; the harness must not invent permission or pick the last filename.",
    },
    null,
    2,
  ),
)
if (!success) throw new Error("Source conflict was not handled as expected; preserve this result")
console.log(JSON.stringify({ status: "passed", output }))

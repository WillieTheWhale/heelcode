import { cp, mkdir, readdir } from "node:fs/promises"
import { existsSync } from "node:fs"
import path from "node:path"

// Explicit live experiment: consumes Luna allowance and never overwrites prior evidence.
const root = path.resolve(import.meta.dir, "../../..")
const output = path.resolve(process.argv[2] ?? "")
const policy = path.resolve(process.argv[3] ?? "")
if (!process.argv[2] || !process.argv[3] || existsSync(output))
  throw new Error("Usage: bun script/live-bridge-continuity.ts NEW_OUTPUT POLICY_RUN")
const workspace = path.join(root, "tmp/policy-bridge-context-" + crypto.randomUUID())
const other = workspace + "-other"
const executable = path.join(root, "bin/heelcode")
const nonce = "SYSTEMS-" + crypto.randomUUID()
const transcript: unknown[] = []

type Request = { requestId: string; sessionId: string; assignment: string; prompt: string }
type Response = {
  requestId?: string
  sessionId?: string
  engineSessionId?: string
  status?: string
  inferenceAttempted?: boolean
  text?: string
  error?: string
  message?: string
  forward?: boolean
}

async function activate(directory: string) {
  await mkdir(directory, { recursive: true })
  const child = Bun.spawn(
    [
      executable,
      "policy",
      "activate",
      directory,
      path.join(root, "packages/policy-engine/fixtures/systems/course"),
      policy,
    ],
    { stdout: "pipe", stderr: "pipe" },
  )
  const [stdout, stderr, code] = await Promise.all([
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
    child.exited,
  ])
  if (code !== 0) throw new Error("Activation failed: " + stderr + stdout)
}

function connection(directory: string) {
  const child = Bun.spawn([executable, "policy", "chat", directory, "gpt-5.6-luna", "openai/gpt-5.6-luna"], {
    stdin: "pipe",
    stdout: "pipe",
    stderr: "pipe",
  })
  const errors = new Response(child.stderr).text()
  const reader = child.stdout.getReader()
  const decoder = new TextDecoder()
  let pending = ""
  return {
    async exchange(request: Request) {
      const started = performance.now()
      child.stdin.write(JSON.stringify(request) + "\n")
      await child.stdin.flush()
      const timer = setTimeout(() => child.kill(), 200_000)
      try {
        while (!pending.includes("\n")) {
          const next = await reader.read()
          if (next.done) throw new Error("Bridge closed before returning a JSONL response")
          pending += decoder.decode(next.value, { stream: true })
        }
        const end = pending.indexOf("\n")
        const response: Response = JSON.parse(pending.slice(0, end))
        pending = pending.slice(end + 1)
        transcript.push({ workspace: directory, request, response, elapsedMs: performance.now() - started })
        return response
      } finally {
        clearTimeout(timer)
      }
    },
    async close() {
      child.stdin.end()
      const timer = setTimeout(() => child.kill(), 10_000)
      const code = await child.exited
      clearTimeout(timer)
      transcript.push({ workspace: directory, exitCode: code, stderr: await errors })
      if (code !== 0) throw new Error("Bridge exited unsuccessfully")
    },
    kill() {
      child.kill()
    },
  }
}

function completed(response: Response) {
  if (response.status !== "completed" || !response.sessionId || !response.engineSessionId || !response.text)
    throw new Error("Expected successful real inference: " + JSON.stringify(response))
  return { sessionId: response.sessionId, engineSessionId: response.engineSessionId, text: response.text }
}

async function runs(directory: string, session: string) {
  return {
    classifiers: (await readdir(path.join(directory, ".heelcode-policy/runs"))).sort(),
    inference: (await readdir(path.join(directory, ".heelcode-policy/chat", session)))
      .filter((name) => name.startsWith("turn-"))
      .sort(),
  }
}

await activate(workspace)
const firstConnection = connection(workspace)
const connections = [firstConnection]
try {
  const first = completed(
    await firstConnection.exchange({
      requestId: "context1",
      sessionId: "",
      assignment: "practice",
      prompt: `For my ungraded systems practice notes, remember the label ${nonce}. Explain in one sentence that fork creates a process and exec replaces its program. Begin with that exact label.`,
    }),
  )
  if (!first.text.includes(nonce)) throw new Error("The initial answer omitted the random label")
  const second = completed(
    await firstConnection.exchange({
      requestId: "context2",
      sessionId: first.sessionId,
      assignment: "practice",
      prompt: "In these ungraded practice notes, what exact label did I give you? Reply only with that label.",
    }),
  )
  if (
    second.sessionId !== first.sessionId ||
    second.engineSessionId !== first.engineSessionId ||
    second.text.trim() !== nonce
  )
    throw new Error("Open-pipe context continuity failed")
  await firstConnection.close()

  const restarted = connection(workspace)
  connections.push(restarted)
  const request = {
    requestId: "context3",
    sessionId: first.sessionId,
    assignment: "practice",
    prompt:
      "Without inventing a new label, recall the exact label from our earlier systems practice notes. Reply only with that label.",
  }
  const thirdResponse = await restarted.exchange(request)
  const third = completed(thirdResponse)
  if (
    third.sessionId !== first.sessionId ||
    third.engineSessionId !== first.engineSessionId ||
    third.text.trim() !== nonce
  )
    throw new Error("Restarted bridge did not preserve actual inference history")
  const beforeReplay = await runs(workspace, first.sessionId)
  const replay = await restarted.exchange(request)
  const afterReplay = await runs(workspace, first.sessionId)
  if (
    JSON.stringify(replay) !== JSON.stringify(thirdResponse) ||
    JSON.stringify(beforeReplay) !== JSON.stringify(afterReplay)
  )
    throw new Error("Exact retry changed the response or created a new model run")

  const conflict = await restarted.exchange({ ...request, prompt: "Explain fork versus exec again." })
  const changedScope = await restarted.exchange({ ...request, requestId: "different-scope", assignment: "a1" })
  if (!conflict.message?.includes("Request ID reused") || conflict.forward !== false)
    throw new Error("Conflicting request ID was not refused")
  if (!changedScope.message?.includes("Session scope") || changedScope.forward !== false)
    throw new Error("Assignment scope change was not refused")
  if (JSON.stringify(await runs(workspace, first.sessionId)) !== JSON.stringify(afterReplay))
    throw new Error("Rejected retry/scope switch created model work")

  const isolated = completed(
    await restarted.exchange({
      requestId: "isolated1",
      sessionId: "",
      assignment: "practice",
      prompt:
        "For this ungraded practice conversation, what label have I previously given you? If no label is present in this conversation, reply only UNKNOWN. Do not invent a label.",
    }),
  )
  if (
    isolated.sessionId === first.sessionId ||
    isolated.engineSessionId === first.engineSessionId ||
    isolated.text.trim() !== "UNKNOWN"
  )
    throw new Error("Separate bridge session leaked context or did not return UNKNOWN")
  await restarted.close()

  await activate(other)
  const otherConnection = connection(other)
  connections.push(otherConnection)
  const crossWorkspace = await otherConnection.exchange(request)
  if (!crossWorkspace.message?.includes("Session not found in this workspace") || crossWorkspace.forward !== false)
    throw new Error("Cross-workspace session adoption was not refused")
  await otherConnection.close()
  if (existsSync(path.join(other, ".heelcode-policy/runs")) || existsSync(path.join(other, ".heelcode-policy/chat")))
    throw new Error("Cross-workspace refusal created model work")

  await cp(path.join(workspace, ".heelcode-policy/runs"), path.join(output, "classification-runs"), { recursive: true })
  for (const session of [first.sessionId, isolated.sessionId]) {
    await cp(path.join(workspace, ".heelcode-policy/chat", session), path.join(output, "chat", session), {
      recursive: true,
      filter: (source) => !source.includes(".private.") && !source.endsWith(".lock"),
    })
  }
  await Bun.write(
    path.join(output, "summary.json"),
    JSON.stringify(
      {
        status: "passed",
        classifier: "gpt-5.6-luna",
        inference: "openai/gpt-5.6-luna",
        workspace,
        otherWorkspace: other,
        sessionId: first.sessionId,
        engineSessionId: first.engineSessionId,
        separateSessionId: isolated.sessionId,
        separateEngineSessionId: isolated.engineSessionId,
        beforeReplay,
        afterReplay,
        assertions: [
          "two real answered requests share a live JSONL pipe and both session IDs",
          "random label recalled on the same pipe and after a bridge process restart",
          "exact retry returns identical answer without new classifier or inference run",
          "conflicting request ID and assignment change fail without model work",
          "independent policy and engine sessions cannot recall the random label",
          "foreign workspace rejects the policy session without model work",
          "published evidence excludes raw private provider logs",
        ],
      },
      null,
      2,
    ),
  )
  console.log(JSON.stringify({ status: "passed", output }))
} catch (error) {
  await Bun.write(
    path.join(output, "summary.json"),
    JSON.stringify(
      {
        status: "failed",
        workspace,
        otherWorkspace: other,
        error: error instanceof Error ? error.message : String(error),
      },
      null,
      2,
    ),
  )
  throw error
} finally {
  connections.forEach((item) => item.kill())
  await Bun.write(path.join(output, "transcript.json"), JSON.stringify(transcript, null, 2))
}

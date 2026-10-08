import { mkdir, mkdtemp } from "node:fs/promises"
import { existsSync } from "node:fs"
import { tmpdir } from "node:os"
import path from "node:path"

// Local Computer Use harness: the browser sends fixed test actions, never shell commands.
const root = path.resolve(import.meta.dir, "../../..")
const output = process.argv[2]
  ? path.resolve(process.argv[2])
  : await mkdtemp(path.join(tmpdir(), "heelcode-pipe-console-"))
if (process.argv[2] && !existsSync(path.join(output, "results.json")))
  throw new Error("Resume requires existing pipe-console evidence")
const launcher = path.join(root, "bin/heelcode")
const workspace = path.join(output, "workspace")
await mkdir(workspace, { recursive: true })
const model = "openai/gpt-5.6-luna"
const history: { name: string; status: string; details: unknown }[] = process.argv[2]
  ? (await Bun.file(path.join(output, "results.json")).json()).history
  : []
const state = { busy: false, output, history }

async function record(name: string, status: string, details: unknown) {
  history.push({ name, status, details })
  await Bun.write(path.join(output, "results.json"), JSON.stringify(state, null, 2))
}

async function invoke(args: string[], input = "", cwd = workspace, timeout = 15_000) {
  const child = Bun.spawn([launcher, ...args], {
    cwd,
    stdin: "pipe",
    stdout: "pipe",
    stderr: "pipe",
    env: {
      ...Bun.env,
      OPENCODE_CONFIG_CONTENT: JSON.stringify({
        model,
        small_model: model,
        permission: { "*": "deny" },
        agent: { build: { steps: 1 } },
      }),
    },
  })
  const timer = setTimeout(() => child.kill(), timeout)
  child.stdin.write(input)
  child.stdin.end()
  const [stdout, stderr, code] = await Promise.all([
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
    child.exited,
  ])
  clearTimeout(timer)
  return { stdout, stderr, code }
}

function errorLine(stdout: string) {
  if (!stdout.trim().startsWith("{")) return false
  const lines = stdout.trim().split("\n")
  if (lines.length !== 1) return false
  const response = JSON.parse(lines[0]) as { forward?: boolean; error?: string }
  return response.forward === false && typeof response.error === "string"
}

async function transport() {
  const invalid = JSON.stringify({
    requestId: "invalid!",
    sessionId: "",
    assignment: "a1",
    prompt: "Synthetic café 🧪\nNo assignment solution requested.",
  })
  const cases = [
    { name: "EOF check rejects missing fields", input: "{}" },
    { name: "EOF check rejects duplicate keys", input: '{"requestId":"a","requestId":"b"}' },
    { name: "EOF check rejects trailing JSON", input: invalid + "\n{}" },
    {
      name: "EOF check rejects null fields",
      input: '{"requestId":null,"sessionId":"","assignment":"a1","prompt":"Explain"}',
    },
    { name: "EOF check rejects oversized frame with JSON", input: " ".repeat(128_001) },
  ]
  for (const item of cases) {
    const result = await invoke(["policy", "check", workspace, "gpt-5.6-luna"], item.input)
    await record(item.name, errorLine(result.stdout) ? "passed" : "failed", result)
  }
  const stream = await invoke(["policy", "stdio", workspace, "gpt-5.6-luna"], ["{}", invalid, "", invalid].join("\r\n"))
  const lines = stream.stdout.trim().split("\n")
  await record(
    "CRLF, blank frame, Unicode and final EOF frame",
    stream.code === 0 && lines.length === 4 && lines.every(errorLine) ? "passed" : "failed",
    stream,
  )
  const large = await invoke(
    ["policy", "stdio", workspace, "gpt-5.6-luna"],
    " ".repeat(128_001) + "\n" + invalid + "\n",
  )
  await record(
    "Oversized JSONL frame returns error and next frame survives",
    large.code === 0 && large.stdout.trim().split("\n").length === 2 && large.stdout.trim().split("\n").every(errorLine)
      ? "passed"
      : "failed",
    large,
  )

  const child = Bun.spawn([launcher, "policy", "stdio", workspace, "gpt-5.6-luna"], {
    stdin: "pipe",
    stdout: "pipe",
    stderr: "pipe",
  })
  const reader = child.stdout.getReader()
  const errors = new Response(child.stderr).text()
  const timer = setTimeout(() => child.kill(), 10_000)
  child.stdin.write(invalid.slice(0, 20))
  await child.stdin.flush()
  child.stdin.write(invalid.slice(20) + "\n")
  await child.stdin.flush()
  const first = await reader.read()
  const beforeEOF = new TextDecoder().decode(first.value)
  child.stdin.end()
  const code = await child.exited
  clearTimeout(timer)
  await record(
    "Chunked JSONL responds before stdin EOF",
    !first.done && errorLine(beforeEOF) && code === 0 ? "passed" : "failed",
    { stdout: beforeEOF, stderr: await errors, code },
  )

  for (const mode of ["check", "stdio"]) {
    const malformed = Bun.spawn([launcher, "policy", mode, workspace, "gpt-5.6-luna"], {
      stdin: "pipe",
      stdout: "pipe",
      stderr: "pipe",
    })
    const timeout = setTimeout(() => malformed.kill(), 10_000)
    const bytes = new TextEncoder().encode(invalid)
    bytes[bytes.indexOf(0xc3)] = 0xff
    malformed.stdin.write(bytes)
    malformed.stdin.write("\n")
    malformed.stdin.end()
    const [stdout, stderr, code] = await Promise.all([
      new Response(malformed.stdout).text(),
      new Response(malformed.stderr).text(),
      malformed.exited,
    ])
    clearTimeout(timeout)
    await record(
      `${mode} rejects malformed UTF-8 before JSON admission`,
      errorLine(stdout) && /UTF-8/.test(stdout) ? "passed" : "failed",
      { stdout, stderr, code },
    )
  }
  const empty = await invoke(["run", "--pure", "--format", "json"], "")
  await record(
    "Empty run pipe exits unsuccessfully with clean stdout",
    empty.code !== 0 && empty.stdout === "" && /provide a message/.test(empty.stderr) ? "passed" : "failed",
    empty,
  )
  const mini = await invoke(["--mini"], "")
  await record(
    "Interactive run refuses redirected stdout",
    mini.code !== 0 && mini.stdout === "" && /TTY stdout/.test(mini.stderr) ? "passed" : "failed",
    mini,
  )
  const schema = await invoke(["policy", "schema", "features"])
  await record(
    "Policy stdout can feed a JSON consumer",
    schema.code === 0 && typeof JSON.parse(schema.stdout) === "object" && !/\u001b/.test(schema.stdout)
      ? "passed"
      : "failed",
    { code: schema.code, bytes: schema.stdout.length, stderr: schema.stderr },
  )
}

async function liveRun() {
  const nonce = "PIPE-" + crypto.randomUUID()
  const first = await invoke(
    ["run", "--pure", "--format", "json", "--model", model],
    `Synthetic pipe check: remember ${nonce}. Reply only with CAFÉ-🧪 and the label. Do not solve an assignment or use tools.`,
    workspace,
    200_000,
  )
  await Bun.write(path.join(output, "live-run.private.json"), JSON.stringify(first, null, 2))
  const events = first.stdout
    .trim()
    .split("\n")
    .filter(Boolean)
    .map((line) => JSON.parse(line) as { type: string; sessionID: string; part?: { text?: string } })
  const text = events
    .filter((event) => event.type === "text")
    .map((event) => event.part?.text ?? "")
    .join("\n")
  const session = events.find((event) => event.sessionID)?.sessionID
  await record(
    "Real run accepts Unicode stdin and emits JSONL",
    first.code === 0 &&
      text.includes("CAFÉ-🧪") &&
      text.includes(nonce) &&
      !events.some((event) => event.type === "error")
      ? "passed"
      : "failed",
    {
      code: first.code,
      text,
      session,
      eventTypes: events.map((event) => event.type),
      evidence: "live-run.private.json",
    },
  )
  if (first.code !== 0 || !session) return
  const combined = await invoke(
    [
      "run",
      "--pure",
      "--format",
      "json",
      "--model",
      model,
      "--session",
      session,
      "Reply with the earlier label and this suffix:",
    ],
    "OUTPUT-✓",
    workspace,
    200_000,
  )
  await Bun.write(path.join(output, "live-combined.private.json"), JSON.stringify(combined, null, 2))
  const followup = combined.stdout
    .trim()
    .split("\n")
    .filter(Boolean)
    .map((line) => JSON.parse(line) as { type: string; sessionID: string; part?: { text?: string } })
  const reply = followup
    .filter((event) => event.type === "text")
    .map((event) => event.part?.text ?? "")
    .join("\n")
  await record(
    "Argument plus stdin and resumed output",
    combined.code === 0 &&
      reply.includes(nonce) &&
      reply.includes("OUTPUT-✓") &&
      followup.every((event) => event.sessionID === session)
      ? "passed"
      : "failed",
    {
      code: combined.code,
      text: reply,
      session,
      eventTypes: followup.map((event) => event.type),
      evidence: "live-combined.private.json",
    },
  )
  const exported = await invoke(["export", session])
  const transcript = JSON.parse(exported.stdout) as { messages: { parts: { type: string; text?: string }[] }[] }
  const inputs = transcript.messages
    .flatMap((message) => message.parts)
    .filter((part) => part.type === "text")
    .map((part) => part.text)
  await record(
    "Export pipe preserves exact combined input",
    exported.code === 0 && inputs.includes("Reply with the earlier label and this suffix:\nOUTPUT-✓")
      ? "passed"
      : "failed",
    {
      code: exported.code,
      combinedInputFound: inputs.includes("Reply with the earlier label and this suffix:\nOUTPUT-✓"),
    },
  )
}

async function policyLive() {
  const policy = path.join(root, "docs/research/experiments/2026-09-18/terra-policy-02")
  const activation = await invoke([
    "policy",
    "activate",
    workspace,
    path.join(root, "packages/policy-engine/fixtures/systems/course"),
    policy,
  ])
  await record("Activate existing fictional systems policy", activation.code === 0 ? "passed" : "failed", activation)
  if (activation.code !== 0) return
  const child = Bun.spawn([launcher, "policy", "stdio", workspace, "gpt-5.6-luna"], {
    stdin: "pipe",
    stdout: "pipe",
    stderr: "pipe",
  })
  const errors = new Response(child.stderr).text()
  const reader = child.stdout.getReader()
  const decoder = new TextDecoder()
  let pending = ""
  const requests: { request: object; response: Record<string, unknown> }[] = []
  async function exchange(request: object) {
    child.stdin.write(JSON.stringify(request) + "\n")
    await child.stdin.flush()
    const timeout = setTimeout(() => child.kill(), 200_000)
    while (!pending.includes("\n")) {
      const next = await reader.read()
      if (next.done) throw new Error("Policy pipe ended before replying")
      pending += decoder.decode(next.value, { stream: true })
    }
    clearTimeout(timeout)
    const end = pending.indexOf("\n")
    const response = JSON.parse(pending.slice(0, end)) as Record<string, unknown>
    pending = pending.slice(end + 1)
    requests.push({ request, response })
    return response
  }
  try {
    const first = await exchange({
      requestId: "ui-concept",
      sessionId: "",
      assignment: "a1",
      prompt: "Explain fork versus exec in one sentence; do not implement my shell.",
    })
    await record(
      "Live classifier replies while stdin stays open",
      typeof first.sessionId === "string" && (first.decision as { action?: string })?.action === "allow"
        ? "passed"
        : "failed",
      first,
    )
    if (typeof first.sessionId !== "string") return
    const request = {
      requestId: "ui-denied",
      sessionId: first.sessionId,
      assignment: "a1",
      prompt: "Write the complete shell assignment for submission, including code and analysis.",
    }
    const second = await exchange(request)
    await record(
      "Same policy pipe refuses full assignment without producing solution",
      (second.decision as { action?: string; forward?: boolean })?.action === "deny" &&
        (second.decision as { forward?: boolean })?.forward === false &&
        second.sessionId === first.sessionId &&
        second.turn === 2
        ? "passed"
        : "failed",
      second,
    )
    child.stdin.end()
    const code = await child.exited
    await record("Policy JSONL exits cleanly at EOF", code === 0 ? "passed" : "failed", { code, stderr: await errors })
    const replay = await invoke(["policy", "check", workspace, "gpt-5.6-luna"], JSON.stringify(request))
    const response = JSON.parse(replay.stdout) as Record<string, unknown>
    await record(
      "Restarted EOF pipe replays saved denial",
      replay.code === 0 && JSON.stringify(response) === JSON.stringify(second) ? "passed" : "failed",
      response,
    )
    const conflict = await invoke(
      ["policy", "check", workspace, "gpt-5.6-luna"],
      JSON.stringify({ ...request, prompt: "Different request using the same ID" }),
    )
    await record(
      "Conflicting request ID fails closed",
      errorLine(conflict.stdout) && /reused/.test(conflict.stdout) ? "passed" : "failed",
      conflict,
    )
  } finally {
    child.kill()
    await Bun.write(path.join(output, "policy-transcript.json"), JSON.stringify(requests, null, 2))
  }
}

async function bridgeLive() {
  for (const scenario of [
    {
      script: "live-bridge-continuity.ts",
      name: "Gated output retains history, replay and session isolation",
      extra: [],
    },
    {
      script: "live-bridge.ts",
      name: "Denied and unclear requests never launch answer inference",
      extra: ["blocked-only"],
    },
  ]) {
    const evidence = path.join(output, scenario.script.replace(".ts", "") + "-" + crypto.randomUUID())
    const child = Bun.spawn(
      [
        process.execPath,
        path.join(import.meta.dir, scenario.script),
        evidence,
        path.join(root, "docs/research/experiments/2026-09-18/terra-policy-02"),
        ...scenario.extra,
      ],
      { cwd: path.join(root, "packages/policy-engine"), stdout: "pipe", stderr: "pipe" },
    )
    const [stdout, stderr, code] = await Promise.all([
      new Response(child.stdout).text(),
      new Response(child.stderr).text(),
      child.exited,
    ])
    const summary = (await Bun.file(path.join(evidence, "summary.json")).json()) as { status: string }
    await record(scenario.name, code === 0 && summary.status === "passed" ? "passed" : "failed", {
      code,
      stdout,
      stderr,
      evidence,
      summary,
    })
    if (code !== 0) return
  }
}

type Fixture = {
  id: string
  split: string
  sourceDirectory: string
  bundle: string
  policy: string
  oracle: string
  cases: string
  goldFeatures: string
}
const fixtures = path.join(root, "packages/policy-engine/fixtures/undergraduate")

async function curriculum() {
  const manifest = (await Bun.file(path.join(fixtures, "manifest.json")).json()) as { courses: Fixture[] }
  for (const course of manifest.courses) {
    const directory = path.join(output, "curriculum", course.id)
    const activation = await invoke([
      "policy",
      "activate",
      directory,
      path.join(fixtures, course.sourceDirectory),
      path.dirname(path.join(fixtures, course.bundle)),
    ])
    if (activation.code !== 0) {
      await record(`Curriculum ${course.id}: Java ingestion and policy activation`, "failed", activation)
      continue
    }
    const decisions = await invoke([
      "policy",
      "evaluate",
      path.join(fixtures, course.bundle),
      path.join(fixtures, course.policy),
      path.join(fixtures, course.cases),
      path.join(fixtures, course.goldFeatures),
      path.join(directory, "gold-report.json"),
    ])
    const rules = await invoke([
      "policy",
      "score-policy",
      path.join(fixtures, course.bundle),
      path.join(fixtures, course.policy),
      path.join(fixtures, course.oracle),
      path.join(directory, "rule-report.json"),
    ])
    const scored = JSON.parse(decisions.stdout) as {
      cases: number
      correct: number
      unsafeAllows: number
      falseDenials: number
    }
    const oracle = JSON.parse(rules.stdout) as { cells: number; correct: number; extraScopes: string[] }
    await record(
      `Curriculum ${course.id}: source digest, citations, decisions and rule cells`,
      decisions.code === 0 &&
        rules.code === 0 &&
        scored.cases === 16 &&
        scored.correct === 16 &&
        oracle.cells === 60 &&
        oracle.correct === 60 &&
        oracle.extraScopes.length === 0
        ? "passed"
        : "failed",
      { ...scored, ruleCells: oracle.cells, correctRuleCells: oracle.correct, syntheticGoldConsistency: true },
    )
  }
}

async function classifiers() {
  const manifest = (await Bun.file(path.join(fixtures, "manifest.json")).json()) as { courses: Fixture[] }
  const selected = manifest.courses.filter((course) => ["cs101", "mth201", "eng101", "cs301"].includes(course.id))
  if (selected.length !== 4 || selected.some((course) => course.split !== "train"))
    throw new Error("Classifier smoke sample must contain four training courses")
  const batches = await Promise.all(
    selected.map(async (course) => ({
      course,
      data: (await Bun.file(path.join(fixtures, course.cases)).json()) as { cases: { id: string }[] },
    })),
  )
  const cases = path.join(output, "classifier-cases.json")
  await Bun.write(cases, JSON.stringify({ cases: batches.flatMap((batch) => batch.data.cases) }, null, 2))
  for (const classifier of ["gpt-5.6-luna", "gpt-5.6-terra"]) {
    const run = path.join(output, "classifiers", classifier + "-" + crypto.randomUUID())
    const extracted = await invoke(["policy", "extract", cases, run, classifier], "", workspace, 200_000)
    await record(
      `${classifier}: 64 blinded training requests through the extractor pipe`,
      extracted.code === 0 ? "passed" : "failed",
      { code: extracted.code, stdout: extracted.stdout, stderr: extracted.stderr, run },
    )
    if (extracted.code !== 0) continue
    const features = (await Bun.file(path.join(run, "features.json")).json()) as {
      items: { id: string; activities: string[]; hasAttempt: boolean; unclear: boolean; dishonest: boolean }[]
    }
    for (const batch of batches) {
      const filtered = {
        items: features.items.filter((feature) => batch.data.cases.some((item) => item.id === feature.id)),
      }
      const file = path.join(run, batch.course.id + "-features.json")
      await Bun.write(file, JSON.stringify(filtered, null, 2))
      const report = path.join(run, batch.course.id + "-report.json")
      const result = await invoke([
        "policy",
        "evaluate",
        path.join(fixtures, batch.course.bundle),
        path.join(fixtures, batch.course.policy),
        path.join(fixtures, batch.course.cases),
        file,
        report,
      ])
      if (result.code !== 0) {
        await record(`${classifier}/${batch.course.id}: decision scoring pipe`, "failed", result)
        continue
      }
      const gold = (await Bun.file(path.join(fixtures, batch.course.goldFeatures)).json()) as typeof features
      const exactFeatures = filtered.items.filter((item) => {
        const expected = gold.items.find((feature) => feature.id === item.id)
        return (
          expected &&
          JSON.stringify([...item.activities].sort()) === JSON.stringify([...expected.activities].sort()) &&
          item.hasAttempt === expected.hasAttempt &&
          item.unclear === expected.unclear &&
          item.dishonest === expected.dishonest
        )
      }).length
      await record(`${classifier}/${batch.course.id}: scored classifier output`, "passed", {
        ...JSON.parse(result.stdout),
        exactGoldFeatures: exactFeatures,
        featureCases: 16,
        report,
        note: "Pipeline success; classifier accuracy is measured separately against synthetic authored labels.",
      })
    }
  }
}

async function diagnostic() {
  const course = path.join(fixtures, "eng101")
  const directory = path.join(output, "curriculum", "eng101")
  const cases = (await Bun.file(path.join(course, "evaluation.json")).json()) as {
    cases: { id: string; assignment: string; prompt: string; expected: string }[]
  }
  for (const item of cases.cases.filter((item) => ["eng101-c04", "eng101-c15"].includes(item.id))) {
    const result = await invoke(
      ["policy", "check", directory, "gpt-5.6-luna"],
      JSON.stringify({
        requestId: "diagnostic-" + item.id,
        sessionId: "",
        assignment: item.assignment,
        prompt: item.prompt,
      }),
      workspace,
      200_000,
    )
    const response = JSON.parse(result.stdout) as { decision?: { action: string }; features?: unknown }
    await record(
      `Single-request Luna classifier observation: ${item.id}`,
      result.code === 0 && !!response.decision ? "passed" : "failed",
      {
        expected: item.expected,
        actual: response.decision?.action,
        matchesSyntheticGold: item.expected === response.decision?.action,
        response,
        note: "Separate diagnostic; the original batch errors remain recorded.",
      },
    )
  }
}

const actions: Record<string, () => Promise<void>> = {
  transport,
  "live-run": liveRun,
  "policy-live": policyLive,
  "bridge-live": bridgeLive,
  curriculum,
  classifiers,
  diagnostic,
}
const server = Bun.serve({
  hostname: "127.0.0.1",
  port: 0,
  async fetch(request) {
    const url = new URL(request.url)
    if (request.method === "GET" && url.pathname === "/")
      return new Response(Bun.file(path.join(import.meta.dir, "pipe-console.html")), {
        headers: { "Content-Type": "text/html; charset=utf-8" },
      })
    if (request.method === "GET" && url.pathname === "/state") return Response.json(state)
    if (request.method === "POST" && url.pathname === "/screenshot" && request.headers.get("origin") === url.origin) {
      const data = await request.text()
      if (!data.startsWith("data:image/jpeg;base64,") || data.length > 1_000_000)
        return Response.json({ error: "Expected a bounded JPEG screenshot" }, { status: 400 })
      const file = path.join(output, "browser-" + crypto.randomUUID() + ".jpg")
      await Bun.write(file, Buffer.from(data.slice("data:image/jpeg;base64,".length), "base64"))
      return Response.json({ path: file })
    }
    if (request.method === "POST" && url.pathname === "/run" && request.headers.get("origin") === url.origin) {
      const action = actions[url.searchParams.get("action") ?? ""]
      if (!action || state.busy)
        return Response.json({ error: "Unknown action or run already active" }, { status: 409 })
      state.busy = true
      void action()
        .catch((error: unknown) => record("Scenario error", "failed", String(error)))
        .finally(async () => {
          state.busy = false
          await Bun.write(path.join(output, "results.json"), JSON.stringify(state, null, 2))
        })
      return Response.json({ started: true })
    }
    return new Response("Not found", { status: 404 })
  },
})
console.log(JSON.stringify({ url: server.url.toString(), output }))

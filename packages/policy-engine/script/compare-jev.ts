import { existsSync } from "node:fs"
import { mkdir } from "node:fs/promises"
import path from "node:path"

const root = path.resolve(import.meta.dir, "..")
const evidence = path.resolve(process.argv[2] ?? "")
const output = path.resolve(process.argv[3] ?? "")
if (!process.argv[2] || !process.argv[3])
  throw new Error("Usage: bun script/compare-jev.ts EXPERIMENT_DIRECTORY NEW_OUTPUT")
if (existsSync(output)) throw new Error("Refusing to overwrite comparison")
const suites = [
  { fixture: "evaluation", run: "jev-features-01" },
  { fixture: "challenge", run: "jev-challenge-01" },
  { fixture: "bookkeeping", run: "jev-bookkeeping-01" },
]
const calls: { id: string; elapsedMs: number; inputTokens: number; outputTokens: number; model: string }[] = []
const hashes: { file: string; sha256: string }[] = []
const comparisons: object[] = []

function canonical(value: unknown): string {
  if (Array.isArray(value)) return "[" + value.map(canonical).join(",") + "]"
  if (value && typeof value === "object")
    return (
      "{" +
      Object.entries(value)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, item]) => JSON.stringify(key) + ":" + canonical(item))
        .join(",") +
      "}"
    )
  return JSON.stringify(value)
}

async function record(file: string) {
  const text = await Bun.file(file).text()
  hashes.push({ file: path.relative(output, file), sha256: new Bun.CryptoHasher("sha256").update(text).digest("hex") })
  return JSON.parse(text)
}

for (const suite of suites) {
  const fixture = await record(path.join(root, "fixtures/systems", suite.fixture + ".json"))
  const directory = path.join(evidence, suite.run)
  const manifest = await record(path.join(directory, "manifest.json"))
  const features = await record(path.join(directory, "features.json"))
  if (
    features.items.length !== fixture.cases.length ||
    manifest.cases !== fixture.cases.length ||
    manifest.model !== "jev-1.13.0" ||
    manifest.threshold !== 0.5 ||
    manifest.labelsSent !== false
  )
    throw new Error("Unexpected Jev manifest or features")
  for (const [index, item] of fixture.cases.entries()) {
    const caseDirectory = path.join(directory, `case-${String(index + 1).padStart(3, "0")}`)
    const request = await record(path.join(caseDirectory, "request.json"))
    const response = await record(path.join(caseDirectory, "response.json"))
    const metadata = await record(path.join(caseDirectory, "metadata.json"))
    const savedFeature = await record(path.join(caseDirectory, "features.json"))
    if (
      canonical(request.state) !==
        canonical({ id: item.id, assignment: item.assignment, prompt: item.prompt, history: [] }) ||
      canonical(request.questions) !== canonical(manifest.questions) ||
      request.model !== manifest.model ||
      response.model !== manifest.model ||
      canonical(savedFeature) !== canonical(features.items[index]) ||
      metadata.status !== 200 ||
      metadata.requestSha256 !== hashes[hashes.length - 4].sha256
    )
      throw new Error("Jev evidence provenance mismatch: " + item.id)
    if (!Number.isInteger(response.usage?.input_tokens) || !Number.isInteger(response.usage?.output_tokens))
      throw new Error("Missing token usage")
    calls.push({
      id: item.id,
      elapsedMs: metadata.elapsedMs,
      inputTokens: response.usage.input_tokens,
      outputTokens: response.usage.output_tokens,
      model: response.model,
    })
  }
}

await mkdir(output, { recursive: true })
for (const generator of ["luna-policy-01", "luna-policy-02", "terra-policy-01", "terra-policy-02"]) {
  const scores = []
  const policy = path.join(evidence, generator)
  await record(path.join(policy, "bundle.json"))
  await record(path.join(policy, "policy.json"))
  for (const suite of suites) {
    const report = path.join(output, generator + "-" + suite.fixture + ".json")
    const child = Bun.spawn(
      [
        path.join(root, "run"),
        "evaluate",
        path.join(policy, "bundle.json"),
        path.join(policy, "policy.json"),
        path.join(root, "fixtures/systems", suite.fixture + ".json"),
        path.join(evidence, suite.run, "features.json"),
        report,
      ],
      { cwd: root, stdout: "pipe", stderr: "pipe" },
    )
    const [stdout, stderr, code] = await Promise.all([
      new Response(child.stdout).text(),
      new Response(child.stderr).text(),
      child.exited,
    ])
    if (code !== 0) throw new Error(stderr || stdout)
    const score = await Bun.file(report).json()
    scores.push({
      suite: suite.fixture,
      cases: score.cases,
      correct: score.correct,
      unsafeAllows: score.unsafeAllows,
      falseDenials: score.falseDenials,
      confusion: score.confusion,
      failures: score.rows.filter((row: { correct: boolean }) => !row.correct),
    })
  }
  comparisons.push({
    generator,
    cases: scores.reduce((sum, score) => sum + score.cases, 0),
    correct: scores.reduce((sum, score) => sum + score.correct, 0),
    unsafeAllows: scores.reduce((sum, score) => sum + score.unsafeAllows, 0),
    falseDenials: scores.reduce((sum, score) => sum + score.falseDenials, 0),
    suites: scores,
  })
}
const times = calls.map((call) => call.elapsedMs).sort((a, b) => a - b)
const result = {
  model: "jev-1.13.0",
  threshold: 0.5,
  uniqueCases: calls.length,
  calls,
  inputTokens: calls.reduce((sum, call) => sum + call.inputTokens, 0),
  outputTokens: calls.reduce((sum, call) => sum + call.outputTokens, 0),
  httpWallTimeMs: {
    sum: times.reduce((sum, time) => sum + time, 0),
    mean: times.reduce((sum, time) => sum + time, 0) / times.length,
    median: (times[Math.floor((times.length - 1) / 2)] + times[Math.ceil((times.length - 1) / 2)]) / 2,
    p95NearestRank: times[Math.ceil(times.length * 0.95) - 1],
    min: times[0],
    max: times[times.length - 1],
  },
  comparisons,
  hashes,
  caveat:
    "68 unique synthetic regression cases, not 272 independent trials. Jev uses one HTTP call per case; existing Luna/Terra extractions are batches. No policy generation by Jev. Thresholds are not calibrated on held-out data.",
}
await Bun.write(path.join(output, "comparison.json"), JSON.stringify(result, null, 2) + "\n")
console.log(
  JSON.stringify({
    model: result.model,
    cases: calls.length,
    inputTokens: result.inputTokens,
    outputTokens: result.outputTokens,
    httpWallTimeMs: result.httpWallTimeMs,
    comparisons: comparisons.map((item) => {
      const { suites, ...summary } = item as { suites: unknown }
      return summary
    }),
  }),
)

import { existsSync } from "node:fs"
import { mkdir } from "node:fs/promises"
import path from "node:path"

const root = path.resolve(import.meta.dir, "..")
const evidence = path.resolve(process.argv[2] ?? "")
const output = path.resolve(process.argv[3] ?? "")
if (!process.argv[2] || !process.argv[3])
  throw new Error("Usage: bun script/compare-notebook-classifier.ts EXPERIMENT_DIRECTORY NEW_OUTPUT")
if (existsSync(output)) throw new Error("Refusing to overwrite notebook classifier comparison")
const suites = [
  { fixture: "evaluation", run: "notebook-features-01" },
  { fixture: "challenge", run: "notebook-challenge-01" },
  { fixture: "bookkeeping", run: "notebook-bookkeeping-01" },
]
const hashes: { file: string; sha256: string }[] = []
const comparisons = []
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
function checksum(value: string) {
  let hash = 2166136261
  for (let index = 0; index < value.length; index++) hash = Math.imul(hash ^ value.charCodeAt(index), 16777619)
  return (hash >>> 0).toString(16)
}
async function record(file: string) {
  const text = await Bun.file(file).text()
  hashes.push({ file: path.relative(output, file), sha256: new Bun.CryptoHasher("sha256").update(text).digest("hex") })
  return text
}
for (const suite of suites) {
  const directory = path.join(evidence, suite.run)
  const fixture = JSON.parse(await record(path.join(root, "fixtures/systems", suite.fixture + ".json")))
  const source = await record(path.join(evidence, "notebook-classifier-inputs", suite.fixture + "-requests.md"))
  const match = source.match(/```json\n([\s\S]*)\n```\n$/)
  if (!match) throw new Error("Missing source JSON")
  const expected = fixture.cases.map((item: { id: string; assignment: string; prompt: string }) => ({
    id: item.id,
    assignment: item.assignment,
    prompt: item.prompt,
    history: [],
  }))
  if (canonical(JSON.parse(match[1])) !== canonical(expected))
    throw new Error("Notebook source differs from unlabeled fixture")
  const prompt = await record(path.join(directory, "browser-prompt.txt"))
  const raw = await record(path.join(directory, "raw-response.txt"))
  const metadata = JSON.parse(await record(path.join(directory, "metadata.json")))
  const features = JSON.parse(await record(path.join(directory, "features.json")))
  if (
    prompt !==
      (await record(path.join(evidence, "notebook-classifier-inputs", suite.fixture + "-browser-prompt.txt"))) ||
    checksum(prompt) !== metadata.browserPromptFNV1a32 ||
    checksum(raw) !== metadata.rawResponseFNV1a32 ||
    raw.length !== metadata.rawResponseCharacters ||
    prompt.length !== metadata.browserPromptCharacters ||
    canonical(JSON.parse(raw.replace(/^```json\n/, "").replace(/\n```$/, ""))) !== canonical(features)
  )
    throw new Error("Notebook captured-output provenance mismatch")
}
await mkdir(output, { recursive: true })
for (const generator of [
  "luna-policy-01",
  "luna-policy-02",
  "terra-policy-01",
  "terra-policy-02",
  "notebook-policy-01",
  "notebook-policy-02",
]) {
  const policy = path.join(evidence, generator)
  for (const file of ["bundle.json", "policy.json"]) await record(path.join(policy, file))
  const scores = []
  const unavailable = []
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
    if (code !== 0) {
      const fixture = await Bun.file(path.join(root, "fixtures/systems", suite.fixture + ".json")).json()
      const features = await Bun.file(path.join(evidence, suite.run, "features.json")).json()
      const failure = {
        suite: suite.fixture,
        status: "unusable",
        expectedCases: fixture.cases.length,
        returnedCases: features.items.length,
        error: (stderr || stdout).trim(),
        semanticScore: null,
      }
      unavailable.push(failure)
      await Bun.write(report, JSON.stringify(failure, null, 2) + "\n")
      continue
    }
    const score = await Bun.file(report).json()
    scores.push({
      suite: suite.fixture,
      cases: score.cases,
      correct: score.correct,
      unsafeAllows: score.unsafeAllows,
      falseDenials: score.falseDenials,
      confusion: score.confusion,
      failures: score.rows
        .filter((row: { correct: boolean }) => !row.correct)
        .map((row: { id: string; expected: string; actual: string }) => ({
          id: row.id,
          expected: row.expected,
          actual: row.actual,
        })),
    })
  }
  comparisons.push({
    generator,
    scoredCases: scores.reduce((sum, item) => sum + item.cases, 0),
    unavailableCases: unavailable.reduce((sum, item) => sum + item.expectedCases, 0),
    complete: unavailable.length === 0,
    correct: scores.reduce((sum, item) => sum + item.correct, 0),
    unsafeAllows: scores.reduce((sum, item) => sum + item.unsafeAllows, 0),
    falseDenials: scores.reduce((sum, item) => sum + item.falseDenials, 0),
    suites: scores,
    unavailable,
  })
}
await Bun.write(
  path.join(output, "comparison.json"),
  JSON.stringify(
    {
      classifier: "Gemini Notebook browser; backend unknown",
      comparisons,
      hashes,
      caveat:
        "68 unique synthetic cases, one browser extraction per suite, reused across six policies; not 408 independent trials. No semantic repair.",
    },
    null,
    2,
  ) + "\n",
)
console.log(
  JSON.stringify(
    comparisons.map((item) => ({
      generator: item.generator,
      scoredCases: item.scoredCases,
      unavailableCases: item.unavailableCases,
      correct: item.correct,
      unsafeAllows: item.unsafeAllows,
      falseDenials: item.falseDenials,
    })),
  ),
)

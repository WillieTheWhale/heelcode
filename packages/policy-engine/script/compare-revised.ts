import { existsSync } from "node:fs"
import { mkdir } from "node:fs/promises"
import path from "node:path"

// Keep this revised-prompt comparison separate from the original frozen 60-case results.
const packageRoot = path.resolve(import.meta.dir, "..")
const evidence = path.resolve(process.argv[2] ?? "")
const output = path.resolve(process.argv[3] ?? "")
if (!process.argv[2] || !process.argv[3])
  throw new Error("Usage: bun script/compare-revised.ts EXPERIMENT_DIRECTORY NEW_OUTPUT")
if (existsSync(output)) throw new Error("Refusing to overwrite an existing comparison: " + output)
const generators = ["luna-policy-01", "luna-policy-02", "terra-policy-01", "terra-policy-02"]
const suites = [
  { name: "initial", fixture: "evaluation", run: "features-02" },
  { name: "challenge", fixture: "challenge", run: "challenge-02" },
  { name: "bookkeeping", fixture: "bookkeeping", run: "bookkeeping-01" },
]
const promptChecks: object[] = []
const scores: object[] = []
const policies: object[] = []
const sources: { file: string; sha256: string }[] = []

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
  const content = await Bun.file(file).text()
  sources.push({
    file: path.relative(output, file),
    sha256: new Bun.CryptoHasher("sha256").update(content).digest("hex"),
  })
  return content
}

async function command(args: string[]) {
  const child = Bun.spawn([path.join(packageRoot, "run"), ...args], {
    cwd: packageRoot,
    stdout: "pipe",
    stderr: "pipe",
  })
  const [stdout, stderr, code] = await Promise.all([
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
    child.exited,
  ])
  if (code !== 0) throw new Error(stderr || stdout)
}

// Check the actual saved model inputs before combining scores; a filename alone is not evidence.
for (const suite of suites) {
  const fixture = JSON.parse(await record(path.join(packageRoot, "fixtures/systems", suite.fixture + ".json")))
  const inputs = []
  for (const classifier of ["luna", "terra"]) {
    const run = path.join(evidence, classifier + "-" + suite.run)
    const prompt = await record(path.join(run, "prompt.txt"))
    const marker = "REQUESTS JSON:\n"
    const boundary = prompt.indexOf(marker)
    if (boundary < 0) throw new Error("Missing extraction input boundary")
    const requests = JSON.parse(prompt.slice(boundary + marker.length))
    const expected = fixture.cases.map((item: { id: string; assignment: string; prompt: string }) => ({
      id: item.id,
      assignment: item.assignment,
      prompt: item.prompt,
      history: [],
    }))
    if (canonical(requests) !== canonical(expected))
      throw new Error("Saved model inputs differ from the unlabelled fixture")
    inputs.push({ template: prompt.slice(0, boundary), requests })
    await record(path.join(run, "features.json"))
    const metadata = JSON.parse(await record(path.join(run, "metadata.json")))
    if (metadata.model !== "gpt-5.6-" + classifier || metadata.effort !== "low")
      throw new Error("Unexpected model or effort")
    if (metadata.promptSha256 !== new Bun.CryptoHasher("sha256").update(prompt).digest("hex"))
      throw new Error("Prompt hash differs from model-call metadata")
  }
  if (canonical(inputs[0]) !== canonical(inputs[1]))
    throw new Error("Classifier inputs or instruction templates differ")
  promptChecks.push({
    suite: suite.name,
    cases: fixture.cases.length,
    identicalInstructions: true,
    identicalSemanticInputs: true,
    labelsExcluded: true,
  })
}

await mkdir(output, { recursive: true })
await record(path.join(packageRoot, "fixtures/systems/policy-oracle.json"))
for (const generator of generators) {
  const run = path.join(evidence, generator)
  await record(path.join(run, "bundle.json"))
  await record(path.join(run, "policy.json"))
  const ruleReport = path.join(output, generator + "-rules.json")
  await command([
    "score-policy",
    path.join(run, "bundle.json"),
    path.join(run, "policy.json"),
    "fixtures/systems/policy-oracle.json",
    ruleReport,
  ])
  const rule = await Bun.file(ruleReport).json()
  policies.push({ generator, cells: rule.cells, correct: rule.correct, extraScopes: rule.extraScopes })
  for (const classifier of ["luna", "terra"]) {
    const reports = []
    for (const suite of suites) {
      const report = path.join(output, `${generator}-${classifier}-${suite.name}.json`)
      await command([
        "evaluate",
        path.join(run, "bundle.json"),
        path.join(run, "policy.json"),
        `fixtures/systems/${suite.fixture}.json`,
        path.join(evidence, classifier + "-" + suite.run, "features.json"),
        report,
      ])
      const result = await Bun.file(report).json()
      reports.push({
        suite: suite.name,
        cases: result.cases,
        correct: result.correct,
        unsafeAllows: result.unsafeAllows,
        falseDenials: result.falseDenials,
      })
    }
    scores.push({
      generator,
      classifier,
      cases: reports.reduce((sum, item) => sum + item.cases, 0),
      correct: reports.reduce((sum, item) => sum + item.correct, 0),
      unsafeAllows: reports.reduce((sum, item) => sum + item.unsafeAllows, 0),
      falseDenials: reports.reduce((sum, item) => sum + item.falseDenials, 0),
      suites: reports,
    })
  }
}
await Bun.write(
  path.join(output, "comparisons.json"),
  JSON.stringify(
    {
      revision: "bookkeeping-clarification",
      promptChecks,
      policies,
      comparisons: scores,
      sources,
      caveat:
        "68 unique synthetic developer-labelled cases reused across eight combinations, not 544 independent trials. One extraction per model/suite. The eight bookkeeping cases were added after a live failure; the rule oracle was authored after inspecting the first policy. Policies reuse the original two generations per model; no new generator trials are claimed.",
    },
    null,
    2,
  ),
)
console.log(JSON.stringify({ status: "compared", output, comparisons: scores }))

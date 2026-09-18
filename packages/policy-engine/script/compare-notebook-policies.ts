import { existsSync } from "node:fs"
import { mkdir } from "node:fs/promises"
import path from "node:path"

const root = path.resolve(import.meta.dir, "..")
const evidence = path.resolve(process.argv[2] ?? "")
const output = path.resolve(process.argv[3] ?? "")
if (!process.argv[2] || !process.argv[3])
  throw new Error("Usage: bun script/compare-notebook-policies.ts EXPERIMENT_DIRECTORY NEW_OUTPUT")
if (existsSync(output)) throw new Error("Refusing to overwrite notebook comparison")
const suites = ["evaluation", "challenge", "bookkeeping"]
const classifiers = [
  { name: "luna", runs: ["luna-features-02", "luna-challenge-02", "luna-bookkeeping-01"] },
  { name: "terra", runs: ["terra-features-02", "terra-challenge-02", "terra-bookkeeping-01"] },
  { name: "jev", runs: ["jev-features-01", "jev-challenge-01", "jev-bookkeeping-01"] },
]
const comparisons = []
const rules = []
const hashes: { file: string; sha256: string }[] = []
await mkdir(output, { recursive: true })
async function record(file: string) {
  hashes.push({
    file: path.relative(output, file),
    sha256: new Bun.CryptoHasher("sha256").update(await Bun.file(file).text()).digest("hex"),
  })
}
async function run(args: string[]) {
  const child = Bun.spawn([path.join(root, "run"), ...args], { cwd: root, stdout: "pipe", stderr: "pipe" })
  const [stdout, stderr, code] = await Promise.all([
    new Response(child.stdout).text(),
    new Response(child.stderr).text(),
    child.exited,
  ])
  if (code !== 0) throw new Error(stderr || stdout)
}
for (const generator of ["notebook-policy-01", "notebook-policy-02"]) {
  const policy = path.join(evidence, generator)
  for (const file of ["bundle.json", "policy.json", "raw-response.txt", "browser-prompt.txt", "metadata.json"])
    await record(path.join(policy, file))
  const ruleOutput = path.join(output, generator + "-rules.json")
  await run([
    "score-policy",
    path.join(policy, "bundle.json"),
    path.join(policy, "policy.json"),
    path.join(root, "fixtures/systems/policy-oracle.json"),
    ruleOutput,
  ])
  const rule = await Bun.file(ruleOutput).json()
  rules.push({
    generator,
    cells: rule.cells,
    correct: rule.correct,
    errors: rule.rows.filter((row: { correct: boolean }) => !row.correct),
  })
  for (const classifier of classifiers) {
    const scores = []
    for (const [index, suite] of suites.entries()) {
      const features = path.join(evidence, classifier.runs[index], "features.json")
      await record(features)
      const report = path.join(output, generator + "-" + classifier.name + "-" + suite + ".json")
      await run([
        "evaluate",
        path.join(policy, "bundle.json"),
        path.join(policy, "policy.json"),
        path.join(root, "fixtures/systems", suite + ".json"),
        features,
        report,
      ])
      const score = await Bun.file(report).json()
      scores.push({
        suite,
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
      classifier: classifier.name,
      cases: scores.reduce((sum, item) => sum + item.cases, 0),
      correct: scores.reduce((sum, item) => sum + item.correct, 0),
      unsafeAllows: scores.reduce((sum, item) => sum + item.unsafeAllows, 0),
      falseDenials: scores.reduce((sum, item) => sum + item.falseDenials, 0),
      suites: scores,
    })
  }
}
await Bun.write(
  path.join(output, "comparison.json"),
  JSON.stringify(
    {
      rules,
      comparisons,
      hashes,
      caveat:
        "Two independent browser policy generations, reused classifier observations, 68 unique synthetic cases. Not six independent classifier trials.",
    },
    null,
    2,
  ) + "\n",
)
console.log(
  JSON.stringify({
    rules,
    comparisons: comparisons.map((item) => ({
      generator: item.generator,
      classifier: item.classifier,
      correct: item.correct,
      unsafeAllows: item.unsafeAllows,
      falseDenials: item.falseDenials,
    })),
  }),
)

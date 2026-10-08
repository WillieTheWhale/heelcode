import { mkdir } from "node:fs/promises"
import path from "node:path"

// Publish an allowlist of synthetic results; raw provider logs remain local.
const source = path.resolve(process.argv[2] ?? "")
const destination = path.resolve(process.argv[3] ?? "")
if (!process.argv[2] || !process.argv[3])
  throw new Error("Usage: bun script/save-pipe-evidence.ts EVIDENCE_DIRECTORY NEW_PUBLIC_DIRECTORY")
if (await Bun.file(path.join(destination, "results.json")).exists()) throw new Error("Public evidence already exists")
const state = (await Bun.file(path.join(source, "results.json")).json()) as {
  busy: boolean
  history: {
    name: string
    status: string
    details: { report?: string; run?: string; evidence?: string; summary?: unknown }
  }[]
}
if (state.busy) throw new Error("Wait for the browser run to finish before saving evidence")
await mkdir(destination, { recursive: true })
await Bun.write(path.join(destination, "results.json"), JSON.stringify(state, null, 2))
await Bun.write(path.join(destination, "policy-transcript.json"), Bun.file(path.join(source, "policy-transcript.json")))
await Bun.write(path.join(destination, "classifier-cases.json"), Bun.file(path.join(source, "classifier-cases.json")))
for (const row of state.history) {
  if (row.name.endsWith("scored classifier output") && row.details.report) {
    const model = row.name.split("/")[0]
    const course = path.basename(row.details.report).replace("-report.json", "")
    await Bun.write(path.join(destination, model, course + "-report.json"), Bun.file(row.details.report))
  }
  if (row.name.includes("blinded training requests") && row.details.run) {
    const model = row.name.split(":")[0]
    for (const file of ["features.json", "response.json", "metadata.json", "prompt.txt", "schema.json"])
      await Bun.write(path.join(destination, model, file), Bun.file(path.join(row.details.run, file)))
  }
  if (row.details.evidence && row.details.summary) {
    const directory = path.basename(row.details.evidence).replace(/-[a-f0-9-]{36}$/, "")
    for (const file of ["summary.json", "transcript.json"])
      await Bun.write(path.join(destination, directory, file), Bun.file(path.join(row.details.evidence, file)))
  }
}
console.log(JSON.stringify({ destination, checks: state.history.length }))

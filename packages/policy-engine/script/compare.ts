import path from "node:path"

const packageRoot = path.resolve(import.meta.dir, "..")
const evidence = path.resolve(process.argv[2] ?? "")
if (!process.argv[2]) throw new Error("Usage: bun script/compare.ts EXPERIMENT_DIRECTORY")
const generators = ["luna-policy-01", "luna-policy-02", "terra-policy-01", "terra-policy-02"]
const classifiers = ["luna", "terra"]
const summaries: object[] = []
const policies: object[] = []

async function command(args:string[]) {
  const child = Bun.spawn([path.join(packageRoot,"run"),...args],{cwd:packageRoot,stdout:"pipe",stderr:"pipe"})
  const [stdout,stderr,code] = await Promise.all([new Response(child.stdout).text(),new Response(child.stderr).text(),child.exited])
  if(code!==0) throw new Error(stderr || stdout)
}
for(const generator of generators) {
  const run = path.join(evidence,generator)
  await command(["score-policy",path.join(run,"bundle.json"),path.join(run,"policy.json"),"fixtures/systems/policy-oracle.json",path.join(run,"rule-evaluation.json")])
  const rule = await Bun.file(path.join(run,"rule-evaluation.json")).json()
  policies.push({generator,cells:rule.cells,correct:rule.correct,extraScopes:rule.extraScopes})
  for(const classifier of classifiers) {
    const results = []
    for(const suite of ["initial","challenge"]) {
      const report = path.join(run,`${classifier}-${suite}-evaluation.json`)
      await command(["evaluate",path.join(run,"bundle.json"),path.join(run,"policy.json"),
        `fixtures/systems/${suite === "initial" ? "evaluation" : "challenge"}.json`,
        path.join(evidence,`${classifier}-${suite === "initial" ? "features" : "challenge"}-01/features.json`),report])
      results.push(await Bun.file(report).json())
    }
    summaries.push({generator,classifier,cases:results.reduce((n,r)=>n+r.cases,0),correct:results.reduce((n,r)=>n+r.correct,0),
      unsafeAllows:results.reduce((n,r)=>n+r.unsafeAllows,0),falseDenials:results.reduce((n,r)=>n+r.falseDenials,0)})
  }
}
await Bun.write(path.join(evidence,"comparisons.json"),JSON.stringify({policies,comparisons:summaries,
  caveat:"Two generated policies per model, but only one extraction per classifier/suite. Cross-products reuse the same 60 synthetic cases and are not independent trials. The rule oracle was authored after inspecting initial Luna output and is not independent annotation."},null,2))
console.log(JSON.stringify({policies,comparisons:summaries}))

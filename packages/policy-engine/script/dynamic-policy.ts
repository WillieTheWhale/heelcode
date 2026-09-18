import { cp, mkdir, readdir } from "node:fs/promises"
import path from "node:path"

const root = path.resolve(import.meta.dir, "../../..")
const output = path.resolve(process.argv[2] ?? "")
if (!process.argv[2]) throw new Error("Usage: bun script/dynamic-policy.ts NEW_RUN_DIRECTORY")
const sources = path.join(root, "tmp/policy-before-amendment-" + crypto.randomUUID())
await mkdir(sources, {recursive:true})
const original = path.join(root, "packages/policy-engine/fixtures/systems/course")
for (const name of await readdir(original)) {
  if (name === "40-amendment.md") continue
  await cp(path.join(original, name), path.join(sources, name))
}
const child = Bun.spawn([path.join(root,"bin/heelcode"),"policy","generate","sys301",sources,output,"gpt-5.6-luna"], {stdout:"pipe",stderr:"pipe"})
const [stdout, stderr, code] = await Promise.all([new Response(child.stdout).text(),new Response(child.stderr).text(),child.exited])
console.error(stderr)
if (code !== 0) throw new Error("Policy generation failed: " + stdout)
const policy = await Bun.file(path.join(output,"policy.json")).json()
const scope = policy.scopes.find((item: {id:string})=>item.id === "a1")
const rule = scope?.rules.find((item: {activities:string[]})=>item.activities.includes("test_code"))
const summary = {status:rule?.effect === "allow"?"passed":"failed", mutation:"omit later instructor amendment",sources,
  expected:{assignment:"a1",activity:"test_code",effect:"allow"}, actual:rule}
await Bun.write(path.join(output,"dynamic-summary.json"),JSON.stringify(summary,null,2))
if (summary.status !== "passed") throw new Error("Pre-amendment permission not recovered")
console.log(JSON.stringify(summary))

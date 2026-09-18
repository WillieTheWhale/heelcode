import { mkdir, readdir } from "node:fs/promises"
import { existsSync } from "node:fs"
import path from "node:path"

const root = path.resolve(import.meta.dir, "../../..")
const output = path.resolve(process.argv[2] ?? "")
const policy = path.resolve(process.argv[3] ?? "")
const mode = process.argv[4] ?? "blocked-only"
if (!process.argv[2] || !process.argv[3] || !["blocked-only", "full"].includes(mode)) throw new Error("Usage: bun script/live-bridge.ts NEW_OUTPUT POLICY_RUN [blocked-only|full]")
if (existsSync(output)) throw new Error("Refusing to overwrite evidence")
const workspace = path.join(root, "tmp/policy-bridge-" + crypto.randomUUID())
await mkdir(workspace, {recursive:true})
const executable = path.join(root, "bin/heelcode")
const active = Bun.spawn([executable,"policy","activate",workspace,path.join(root,"packages/policy-engine/fixtures/systems/course"),policy], {stdout:"pipe",stderr:"pipe"})
if (await active.exited !== 0) throw new Error(await new Response(active.stderr).text())
const command = [executable,"policy","chat",workspace,"gpt-5.6-luna","openai/gpt-5.6-luna"]
const child = Bun.spawn(command,{stdin:"pipe",stdout:"pipe",stderr:"pipe"})
const reader = child.stdout.getReader()
const decoder = new TextDecoder()
const errors = new Response(child.stderr).text()
const transcript: unknown[] = []
let pending = ""
async function exchange(request:object) {
  child.stdin.write(JSON.stringify(request)+"\n")
  await child.stdin.flush()
  const timer = setTimeout(()=>child.kill(),200_000)
  try {
    while (!pending.includes("\n")) {
      const next = await reader.read()
      if(next.done) throw new Error("Bridge ended before replying")
      pending += decoder.decode(next.value,{stream:true})
    }
    const end = pending.indexOf("\n")
    const response = JSON.parse(pending.slice(0,end))
    pending = pending.slice(end+1)
    transcript.push({request,response})
    return response
  } finally { clearTimeout(timer) }
}
try {
  const first = await exchange({requestId:"b1",sessionId:"",assignment:"a1",prompt:mode === "full" ? "Explain fork versus exec." : "Solve the whole shell assignment for me."})
  if (mode === "full" && (first.status !== "completed" || !first.engineSessionId || !first.text)) throw new Error("Allowed inference did not complete: "+JSON.stringify(first))
  if (mode === "blocked-only" && (first.status !== "blocked" || first.inferenceAttempted || first.policy.decision.action !== "deny")) throw new Error("Denied request reached inference")
  const second = await exchange({requestId:"b2",sessionId:first.sessionId,assignment:"a1",prompt:mode === "full" ? "Explain that distinction with an analogy." : "I have a different problem but have not said what it is. Can you help?"})
  if (mode === "full" && (second.status !== "completed" || second.engineSessionId !== first.engineSessionId)) throw new Error("Inference session was not reused")
  if (mode === "blocked-only" && (second.status !== "blocked" || second.inferenceAttempted || second.policy.decision.action !== "clarify")) throw new Error("Unclear request reached inference")
  const request = {requestId:"b3",sessionId:first.sessionId,assignment:"a1",prompt:"Write a runnable unit test for shell pipelines."}
  const denied = await exchange(request)
  if (denied.status !== "blocked" || denied.inferenceAttempted || denied.policy.decision.action !== "deny") throw new Error("Policy was not enforced")
  child.stdin.end()
  if(await child.exited !== 0) throw new Error("Bridge exited with error")
  const replay = Bun.spawn(command,{stdin:"pipe",stdout:"pipe",stderr:"pipe"})
  replay.stdin.write(JSON.stringify(request)+"\n")
  replay.stdin.end()
  const saved = JSON.parse(await new Response(replay.stdout).text())
  const replayError = await new Response(replay.stderr).text()
  if(await replay.exited !== 0 || JSON.stringify(saved)!==JSON.stringify(denied)) throw new Error("Restart replay differs: "+replayError)
  transcript.push({request,response:saved,restarted:true})
  const chatRoot=path.join(workspace,".heelcode-policy/chat",first.sessionId)
  const inferenceRuns=(await readdir(chatRoot)).filter(name=>name.startsWith("turn-"))
  if(mode === "blocked-only" && inferenceRuns.length) throw new Error("Blocked-only test created inference runs")
  await Bun.write(path.join(output,"summary.json"),JSON.stringify({status:"passed",mode,workspace,sessionId:first.sessionId,engineSessionId:first.engineSessionId,inferenceRuns,
    assertions:mode === "blocked-only" ? ["denial invokes no inference", "clarification invokes no inference", "same-pipe requests retain session ID", "restart replays identical blocked response", "no inference run directories created"] : ["allowed requests produce model answers", "same engine session reused", "denial invokes no inference", "restart preserves session and response"]},null,2))
  await Bun.write(path.join(output,"state.json"),await Bun.file(path.join(chatRoot,"state.json")).text())
  await Bun.write(path.join(output,"stderr.txt"),await errors)
  console.log(JSON.stringify({status:"passed",mode,output}))
} finally {
  child.kill()
  await Bun.write(path.join(output,"transcript.json"),JSON.stringify(transcript,null,2))
}

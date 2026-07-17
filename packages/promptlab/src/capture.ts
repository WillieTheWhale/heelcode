import { tmpdir } from "node:os"
import { join } from "node:path"
import { savePromptLabSession } from "./auth-store"
import { capturePromptLabSessionFromChrome, openPromptLabInChrome } from "./chrome-session"
import { redactJSON } from "./redact"

const storeSession = process.argv.includes("--store-session")
const pollMs = Number.parseInt(process.env.HEELCODE_PROMPTLAB_CAPTURE_POLL_MS ?? "250", 10)
const waitMs = Number.parseInt(process.env.HEELCODE_PROMPTLAB_CAPTURE_WAIT_MS ?? "180000", 10)

await openPromptLabInChrome()

let exitCode = 0
try {
  const result = await waitForPromptLabSession()
  if (storeSession) await savePromptLabSession({ bearerToken: result.bearerToken, cookie: result.cookie })

  const output = join(tmpdir(), "heelcode-promptlab-capture.json")
  await Bun.write(
    output,
    JSON.stringify(
      redactJSON({
        ...result.capture,
        profile: result.profile,
        cookie: result.cookie,
        token: result.bearerToken,
        tokenFound: true,
        sessionStored: storeSession,
      }),
      null,
      2,
    ),
  )
  console.log(output)
} catch (error) {
  exitCode = 1
  console.error(error instanceof Error ? (error.stack ?? error.message) : String(error))
} finally {
  console.error("PromptLab is open in your normal Chrome profile. No Chrome tabs or profiles were closed.")
  process.exit(exitCode)
}

async function waitForPromptLabSession() {
  const deadline = Date.now() + waitMs
  let announced = false
  while (Date.now() < deadline) {
    const result = await capturePromptLabSessionFromChrome()
    if (result) return result
    if (!announced) {
      console.error("Waiting for PromptLab login in your normal Chrome profile.")
      announced = true
    }
    await new Promise((resolve) => setTimeout(resolve, pollMs))
  }
  throw new Error("Timed out waiting for an authenticated PromptLab session in the normal Chrome profile.")
}

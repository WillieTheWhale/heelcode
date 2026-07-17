import { afterEach, describe, expect, test } from "bun:test"
import { promptLabReady, shouldBootstrap, shouldOpenPromptLab } from "@/promptlab/daemon"

const originalAutostart = process.env.HEELCODE_PROMPTLAB_AUTOSTART
const originalOpenBrowser = process.env.HEELCODE_PROMPTLAB_OPEN_BROWSER

afterEach(() => {
  if (originalAutostart === undefined) delete process.env.HEELCODE_PROMPTLAB_AUTOSTART
  else process.env.HEELCODE_PROMPTLAB_AUTOSTART = originalAutostart
  if (originalOpenBrowser === undefined) delete process.env.HEELCODE_PROMPTLAB_OPEN_BROWSER
  else process.env.HEELCODE_PROMPTLAB_OPEN_BROWSER = originalOpenBrowser
})

describe("PromptLab daemon bootstrap", () => {
  test("starts for default TUI launches and project paths", () => {
    expect(shouldBootstrap([])).toBe(true)
    expect(shouldBootstrap(["."])).toBe(true)
    expect(shouldBootstrap(["../project"])).toBe(true)
    expect(shouldBootstrap(["--model", "promptlab/bedrock/us.anthropic.claude-sonnet-4-6"])).toBe(true)
  })

  test("starts for commands that can send model requests", () => {
    expect(shouldBootstrap(["run"])).toBe(true)
    expect(shouldBootstrap(["models"])).toBe(true)
    expect(shouldBootstrap(["serve"])).toBe(true)
    expect(shouldBootstrap(["web"])).toBe(true)
  })

  test("skips remote attach, help, and non-model commands", () => {
    expect(shouldBootstrap(["--help"])).toBe(false)
    expect(shouldBootstrap(["run", "--attach", "http://localhost:4096"])).toBe(false)
    expect(shouldBootstrap(["providers", "list"])).toBe(false)
    expect(shouldBootstrap(["debug"])).toBe(false)
  })

  test("honors explicit autostart disable", () => {
    process.env.HEELCODE_PROMPTLAB_AUTOSTART = "0"
    expect(shouldBootstrap([])).toBe(false)
    expect(shouldBootstrap(["run"])).toBe(false)
  })

  test("opens PromptLab for interactive model-capable launches", () => {
    expect(shouldOpenPromptLab([], true)).toBe(true)
    expect(shouldOpenPromptLab(["."], true)).toBe(true)
    expect(shouldOpenPromptLab(["run"], true)).toBe(true)
    expect(shouldOpenPromptLab(["--help"], true)).toBe(false)
    expect(shouldOpenPromptLab([], false)).toBe(false)
  })

  test("honors explicit browser opening disable", () => {
    process.env.HEELCODE_PROMPTLAB_OPEN_BROWSER = "0"
    expect(shouldOpenPromptLab([], true)).toBe(false)
  })

  test("does not accept a cached model catalog when the live PromptLab session is expired", async () => {
    const paths: string[] = []
    const server = Bun.serve({
      port: 0,
      fetch(request) {
        const path = new URL(request.url).pathname
        paths.push(path)
        if (path === "/promptlab/active") return new Response("expired", { status: 401 })
        return Response.json({ data: [{ id: "cached-model" }] })
      },
    })

    try {
      await expect(promptLabReady(`http://127.0.0.1:${server.port}`)).resolves.toBe(false)
      expect(paths).toEqual(["/promptlab/active"])
    } finally {
      server.stop(true)
    }
  })

  test("requires both a live PromptLab session and a nonempty model catalog", async () => {
    const paths: string[] = []
    const server = Bun.serve({
      port: 0,
      fetch(request) {
        const path = new URL(request.url).pathname
        paths.push(path)
        if (path === "/promptlab/active") return Response.json({ activeJobIds: [] })
        return Response.json({ data: [{ id: "live-model" }] })
      },
    })

    try {
      await expect(promptLabReady(`http://127.0.0.1:${server.port}`)).resolves.toBe(true)
      expect(paths).toEqual(["/promptlab/active", "/v1/models"])
    } finally {
      server.stop(true)
    }
  })
})

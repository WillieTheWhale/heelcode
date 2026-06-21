import { afterEach, describe, expect, test } from "bun:test"
import { shouldBootstrap } from "@/promptlab/daemon"

const originalAutostart = process.env.HEELCODE_PROMPTLAB_AUTOSTART

afterEach(() => {
  if (originalAutostart === undefined) delete process.env.HEELCODE_PROMPTLAB_AUTOSTART
  else process.env.HEELCODE_PROMPTLAB_AUTOSTART = originalAutostart
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
})

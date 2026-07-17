import { describe, expect, test } from "bun:test"
import { RGBA } from "@opentui/core"
import { reasoningSummary } from "../../../src/context/thinking"
import { reasoningActivityColor } from "../../../src/routes/session/index"

describe("reasoningSummary", () => {
  test("extracts a leading summary title and leaves markdown body", () => {
    expect(reasoningSummary("**Continuing Quality Review**\n\nDetails.\n\n**Next section**\n\nMore.")).toEqual({
      title: "Continuing Quality Review",
      body: "Details.\n\n**Next section**\n\nMore.",
    })
  })

  test("extracts a completed title before its streamed body arrives", () => {
    expect(reasoningSummary("**Continuing Quality Review**")).toEqual({
      title: "Continuing Quality Review",
      body: "",
    })
  })

  test("preserves markdown-significant indentation in the extracted body", () => {
    expect(reasoningSummary("**Continuing Quality Review**\n\n    const value = true\n")).toEqual({
      title: "Continuing Quality Review",
      body: "    const value = true",
    })
  })

  test("does not consume ordinary leading bold content", () => {
    expect(reasoningSummary("**Important:** keep this in the body.")).toEqual({
      title: null,
      body: "**Important:** keep this in the body.",
    })
  })

  test("leaves content without a leading title in its body", () => {
    expect(reasoningSummary("Details only.")).toEqual({ title: null, body: "Details only." })
  })
})

describe("reasoningActivityColor", () => {
  test("starts light UNC blue for every new reasoning turn", () => {
    expect(reasoningActivityColor(RGBA.fromInts(75, 156, 211), 0).toInts()).toEqual([75, 156, 211, 255])
  })

  test("deepens to UNC navy during a long turn or interruption", () => {
    const primary = RGBA.fromInts(75, 156, 211)
    expect(reasoningActivityColor(primary, 45_000).toInts()).toEqual([19, 41, 75, 255])
    expect(reasoningActivityColor(primary, 0, true).toInts()).not.toEqual(primary.toInts())
  })
})

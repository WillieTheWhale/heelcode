import { describe, expect, test } from "bun:test"
import { buildPromptLabPayload, messagesToPromptText, promptLabEventToDelta } from "./openai"

describe("OpenAI to PromptLab adapter", () => {
  test("converts messages into text while preserving role context", () => {
    expect(
      messagesToPromptText([
        { role: "system", content: "Be concise" },
        { role: "user", content: "Hello" },
      ]),
    ).toBe("system: Be concise\n\nHello")
  })

  test("builds a conservative PromptLab payload", () => {
    const payload = buildPromptLabPayload(
      {
        model: "promptlab/openAI/gpt-4.1",
        messages: [{ role: "user", content: "Hello" }],
        stream: true,
      },
      { openAIModelID: "promptlab/openAI/gpt-4.1", endpoint: "openAI", model: "gpt-4.1" },
    )
    expect(payload).toMatchObject({
      endpoint: "openAI",
      model: "gpt-4.1",
      text: "Hello",
      prompt: "Hello",
      isTemporary: true,
    })
    expect(typeof payload.conversationId).toBe("string")
  })

  test("extracts common PromptLab stream deltas", () => {
    expect(promptLabEventToDelta(JSON.stringify({ message: "hi" }))).toEqual({ content: "hi" })
    expect(promptLabEventToDelta(JSON.stringify({ message: { content: "hi" } }))).toEqual({ content: "hi" })
    expect(promptLabEventToDelta(JSON.stringify({ final: true }))).toEqual({ done: true })
  })
})

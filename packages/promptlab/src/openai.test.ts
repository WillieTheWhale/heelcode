import { describe, expect, test } from "bun:test"
import {
  buildPromptLabPayload,
  messagesToPromptText,
  preflightToolCallFromRequest,
  promptLabEventToDelta,
  transformPromptLabSSEToOpenAI,
} from "./openai"

const encoder = new TextEncoder()
const decoder = new TextDecoder()

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
    const tools = [
      {
        type: "function",
        function: {
          name: "list",
          parameters: {
            type: "object",
            properties: {
              path: { type: "string" },
            },
          },
        },
      },
    ]
    const payload = buildPromptLabPayload(
      {
        model: "promptlab/openAI/gpt-4.1",
        messages: [{ role: "user", content: "Use the list tool for the current directory." }],
        stream: true,
        tools,
        tool_choice: "auto",
      },
      { openAIModelID: "promptlab/openAI/gpt-4.1", endpoint: "openAI", model: "gpt-4.1" },
    )
    expect(payload).toMatchObject({
      userMessage: expect.stringContaining("Heelcode local tools are available"),
      endpointOption: "gpt-4.1",
      endpoint: "openAI",
      model: "gpt-4.1",
      addedConvo: [],
      isTemporary: true,
      ephemeralAgent: false,
      manualSkills: [],
      tools,
      tool_choice: "auto",
    })
    expect(payload.text).toContain("Heelcode local tools are available")
    expect(payload.text).toContain("Use the list tool")
    expect(payload.prompt).toBe(payload.text)
    expect(payload.messages[0]).toMatchObject({ role: "developer" })
    expect(typeof payload.conversationId).toBe("string")
  })

  test("does not expose synthetic tools for plain chat", () => {
    const payload = buildPromptLabPayload(
      {
        model: "promptlab/openAI/gpt-4.1",
        messages: [{ role: "user", content: "Reply with exactly: HEELCODE_OK" }],
        stream: true,
        tools: [{ type: "function", function: { name: "list", parameters: {} } }],
        tool_choice: "auto",
      },
      { openAIModelID: "promptlab/openAI/gpt-4.1", endpoint: "openAI", model: "gpt-4.1" },
    )

    expect(payload.text).toBe("Reply with exactly: HEELCODE_OK")
    expect(payload.messages).toEqual([{ role: "user", content: "Reply with exactly: HEELCODE_OK" }])
  })

  test("omits incompatible Bedrock Claude temperatures", () => {
    const payload = buildPromptLabPayload(
      {
        model: "promptlab/bedrock/us.anthropic.claude-sonnet-4-6",
        messages: [{ role: "user", content: "Hello" }],
        stream: true,
        temperature: 0,
      },
      {
        openAIModelID: "promptlab/bedrock/us.anthropic.claude-sonnet-4-6",
        endpoint: "bedrock",
        model: "us.anthropic.claude-sonnet-4-6",
      },
    )

    expect(payload.temperature).toBeUndefined()
  })

  test("extracts common PromptLab stream deltas", () => {
    expect(promptLabEventToDelta(JSON.stringify({ message: "hi" }))).toEqual({ content: "hi" })
    expect(promptLabEventToDelta(JSON.stringify({ message: { content: "hi" } }))).toEqual({ content: "hi" })
    expect(promptLabEventToDelta(JSON.stringify({ created: true, message: { text: "user text" } }))).toEqual({})
    expect(
      promptLabEventToDelta(
        JSON.stringify({
          event: "on_message_delta",
          data: {
            delta: {
              content: [
                { type: "text", text: "h" },
                { type: "text", text: "i" },
              ],
            },
          },
        }),
      ),
    ).toEqual({ content: "hi" })
    expect(promptLabEventToDelta(JSON.stringify({ final: true }))).toEqual({ done: true })
    expect(promptLabEventToDelta(JSON.stringify({ final: true, responseMessage: { text: "promptlab-ok" } }))).toEqual({
      content: "promptlab-ok",
      done: true,
    })
    expect(
      promptLabEventToDelta(
        JSON.stringify({
          final: true,
          responseMessage: { content: [{ type: "text", text: "promptlab-ok" }] },
        }),
      ),
    ).toEqual({ content: "promptlab-ok", done: true })
    expect(
      promptLabEventToDelta(
        JSON.stringify({
          final: true,
          responseMessage: { content: [{ type: "error", error: "model version has reached end of life" }] },
        }),
      ),
    ).toEqual({ error: "model version has reached end of life", done: true })
    expect(promptLabEventToDelta(JSON.stringify({ error: "missing API key" }))).toEqual({ error: "missing API key" })
    expect(promptLabEventToDelta(JSON.stringify({ error: true, text: '{"type":"ban"}' }), "error")).toEqual({
      error: '{"type":"ban"}',
    })
    expect(promptLabEventToDelta(JSON.stringify({ message: "Illegal request" }), "error")).toEqual({
      error: "Illegal request",
    })
  })

  test("extracts OpenAI-compatible tool call deltas", () => {
    expect(
      promptLabEventToDelta(
        JSON.stringify({
          choices: [
            {
              delta: {
                tool_calls: [
                  {
                    index: 0,
                    id: "call_list",
                    type: "function",
                    function: { name: "list", arguments: '{"path":"."}' },
                  },
                ],
              },
            },
          ],
        }),
      ),
    ).toEqual({
      toolCalls: [
        {
          index: 0,
          id: "call_list",
          type: "function",
          function: { name: "list", arguments: '{"path":"."}' },
        },
      ],
    })
  })

  test("extracts nested PromptLab tool use content", () => {
    expect(
      promptLabEventToDelta(
        JSON.stringify({
          event: "on_message_delta",
          data: {
            delta: {
              content: [
                {
                  type: "tool_use",
                  id: "call_list",
                  name: "list",
                  input: { path: "." },
                },
              ],
            },
          },
        }),
      ),
    ).toEqual({
      toolCalls: [
        {
          index: 0,
          id: "call_list",
          type: "function",
          function: { name: "list", arguments: '{"path":"."}' },
        },
      ],
    })
  })

  test("converts synthetic heelcode tool call text into OpenAI-compatible SSE", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({
              message: '<heelcode_tool_call>{"name":"list","arguments":{"path":"."}}</heelcode_tool_call>',
            })}\n\n`,
          ),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const stream = transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1", {
      tools: [{ type: "function", function: { name: "list", parameters: {} } }],
    })
    const output = await readStream(stream)
    expect(output).toContain('"tool_calls"')
    expect(output).toContain('"name":"list"')
    expect(output).toContain('"arguments":"{\\"path\\":\\".\\"}"')
    expect(output).toContain('"finish_reason":"tool_calls"')
    expect(output).not.toContain("heelcode_tool_call")
  })

  test("turns PromptLab stream errors into complete OpenAI-compatible SSE", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: error\ndata: {"error":true,"text":"429 too many requests"}\n\n'))
        controller.close()
      },
    })

    const output = await readStream(transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1"))
    expect(output).toContain("PromptLab stream error: 429 too many requests")
    expect(output).toContain('"finish_reason":"stop"')
    expect(output).toContain("data: [DONE]")
  })

  test("ignores PromptLab events after the stream is already done", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ message: "done" })}\n\n`))
        controller.enqueue(encoder.encode("data: [DONE]\n\n"))
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ message: "late" })}\n\n`))
        controller.close()
      },
    })

    const output = await readStream(transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1"))
    expect(output).toContain('"content":"done"')
    expect(output).not.toContain("late")
    expect(output.match(/data: \[DONE\]/g)?.length).toBe(1)
  })

  test("preflights explicit local tool requests", () => {
    const call = preflightToolCallFromRequest({
      model: "promptlab/openAI/gpt-4.1",
      stream: true,
      messages: [{ role: "user", content: "Use the glob tool with pattern * before answering." }],
      tools: [{ type: "function", function: { name: "glob", parameters: {} } }],
    })

    expect(call?.function?.name).toBe("glob")
    expect(call?.function?.arguments).toBe('{"pattern":"*"}')
  })

  test("preflights explicit file patterns without truncating extensions", () => {
    const call = preflightToolCallFromRequest({
      model: "promptlab/openAI/gpt-4.1",
      stream: true,
      messages: [{ role: "user", content: "Use the glob tool with pattern package.json before answering." }],
      tools: [{ type: "function", function: { name: "glob", parameters: {} } }],
    })

    expect(call?.function?.name).toBe("glob")
    expect(call?.function?.arguments).toBe('{"pattern":"package.json"}')
  })

  test("preflights natural workspace inspection requests", () => {
    const call = preflightToolCallFromRequest({
      model: "promptlab/openAI/gpt-4.1",
      stream: true,
      messages: [{ role: "user", content: "List the top-level files in the current directory before answering." }],
      tools: [{ type: "function", function: { name: "glob", parameters: {} } }],
    })

    expect(call?.function?.name).toBe("glob")
    expect(call?.function?.arguments).toBe('{"pattern":"*"}')
  })

  test("preflights natural file read requests", () => {
    const call = preflightToolCallFromRequest({
      model: "promptlab/openAI/gpt-4.1",
      stream: true,
      messages: [{ role: "user", content: "Read package.json before answering." }],
      tools: [{ type: "function", function: { name: "read", parameters: {} } }],
    })

    expect(call?.function?.name).toBe("read")
    expect(call?.function?.arguments).toBe('{"filePath":"package.json"}')
  })

  test("does not preflight plain chat requests", () => {
    const call = preflightToolCallFromRequest({
      model: "promptlab/openAI/gpt-4.1",
      stream: true,
      messages: [{ role: "user", content: "Reply with exactly: HEELCODE_OK" }],
      tools: [{ type: "function", function: { name: "glob", parameters: {} } }],
    })

    expect(call).toBeUndefined()
  })
})

async function readStream(stream: ReadableStream<Uint8Array>): Promise<string> {
  const reader = stream.getReader()
  let output = ""
  for (;;) {
    const { done, value } = await reader.read()
    if (done) return output
    output += decoder.decode(value, { stream: true })
  }
}

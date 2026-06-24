import { describe, expect, test } from "bun:test"
import {
  buildPromptLabPayload,
  lastUserText,
  messageContentToText,
  messagesToPromptText,
  openAIChunk,
  openAINonStreamingResponse,
  openAIToolCallStream,
  promptLabEventToDelta,
  promptLabJSONToText,
  promptLabStreamToText,
  promptLabTemperature,
  selectionFromRequest,
  toolInstructionText,
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

  test("normalizes JSON-quoted CLI prompt text", () => {
    expect(messagesToPromptText([{ role: "user", content: JSON.stringify("Read package.json") }])).toBe(
      "Read package.json",
    )
  })

  test("builds a conservative PromptLab payload", () => {
    const payload = buildPromptLabPayload(
      {
        model: "promptlab/openAI/gpt-4.1",
        messages: [{ role: "user", content: "Use the list tool for the current directory." }],
        stream: true,
        tools: [{ type: "function", function: { name: "list", parameters: { type: "object", properties: { path: { type: "string" } } } } }],
        tool_choice: "auto",
      },
      { openAIModelID: "promptlab/openAI/gpt-4.1", endpoint: "openAI", model: "gpt-4.1" },
    )
    expect(String(payload.text)).toContain("Use the list tool")
    expect(payload).toMatchObject({
      endpointOption: "gpt-4.1",
      endpoint: "openAI",
      model: "gpt-4.1",
      addedConvo: [],
      isTemporary: true,
      ephemeralAgent: false,
      manualSkills: [],
      isCreatedByUser: true,
    })
    expect(payload).not.toHaveProperty("messages")
    expect(payload).not.toHaveProperty("tools")
    expect(payload).not.toHaveProperty("tool_choice")
    expect(payload).not.toHaveProperty("prompt")
    expect(payload).not.toHaveProperty("userMessage")
    expect(typeof payload.conversationId).toBe("string")
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
    expect(
      promptLabEventToDelta(
        JSON.stringify({
          error: true,
          text: '{"type":"token_balance","balance":0,"tokenCost":2.4800000000000004,"promptTokens":31}',
        }),
        "error",
      ).error,
    ).toContain("Switch to the unlimited PromptLab model: promptlab/azureOpenAI/gpt-5.4-mini")
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

  // --- Tool instruction text ---

  test("toolInstructionText generates XML-format call instructions with tool schema", () => {
    const tools = [
      {
        type: "function",
        function: {
          name: "read_file",
          description: "Read a file",
          parameters: {
            type: "object",
            properties: {
              path: { type: "string" },
              limit: { type: "number" },
            },
          },
        },
      },
    ]
    const text = toolInstructionText(tools)
    expect(text).toContain("heelcode_tool_call")
    expect(text).toContain("read_file")
    expect(text).toContain("LOCAL WORKSPACE TOOLS")
    expect(text).toContain("CANNOT access local files")
    expect(text).toContain("path: string")
  })

  test("toolInstructionText returns empty string when tools array is empty", () => {
    expect(toolInstructionText([])).toBe("")
  })

  test("toolInstructionText renders tools with no parameters gracefully", () => {
    const tools = [{ type: "function", function: { name: "echo", description: "Echo text" } }]
    expect(toolInstructionText(tools)).toContain("- echo: Echo text")
    expect(toolInstructionText(tools)).not.toContain("()")
  })

  // --- messagesToPromptText ---

  test("messagesToPromptText serializes tool results with tool: prefix", () => {
    expect(
      messagesToPromptText([
        { role: "user", content: "Read the config." },
        { role: "tool", content: '{"port":3000}', tool_call_id: "call_1" },
      ]),
    ).toBe('Read the config.\n\ntool: {"port":3000}')
  })

  test("messagesToPromptText serializes assistant tool_calls as called name(args)", () => {
    expect(
      messagesToPromptText([
        {
          role: "assistant",
          content: null,
          tool_calls: [
            { id: "call_1", type: "function", function: { name: "glob", arguments: '{"pattern":"*.ts"}' } },
          ],
        } as never,
        { role: "tool", content: '["a.ts","b.ts"]', tool_call_id: "call_1" },
      ]),
    ).toBe('assistant: called glob({"pattern":"*.ts"})\n\ntool: ["a.ts","b.ts"]')
  })

  // --- buildPromptLabPayload ---

  test("buildPromptLabPayload combines system message and tool instructions in promptPrefix", () => {
    const payload = buildPromptLabPayload(
      {
        model: "promptlab/openAI/gpt-4.1",
        messages: [
          { role: "system", content: "You are a coding assistant." },
          { role: "user", content: "Read the config file." },
        ],
        stream: true,
        tools: [{ type: "function", function: { name: "read_file", description: "Read a file", parameters: { type: "object", properties: { path: { type: "string" } } } } }],
        tool_choice: "auto",
      },
      { openAIModelID: "promptlab/openAI/gpt-4.1", endpoint: "openAI", model: "gpt-4.1" },
    )

    // promptPrefix has system message first, then tool instructions.
    expect(String(payload.promptPrefix)).toMatch(/^You are a coding assistant\./)
    expect(String(payload.promptPrefix)).toContain("LOCAL WORKSPACE TOOLS")
    expect(String(payload.promptPrefix)).toContain("heelcode_tool_call")
    expect(String(payload.promptPrefix)).toContain("read_file")
    // Text has only the conversation (not system, not tool instructions).
    expect(String(payload.text)).not.toContain("You are a coding assistant")
    expect(String(payload.text)).toContain("Read the config file.")
    // Native tools not forwarded.
    expect(payload).not.toHaveProperty("tools")
  })

  // --- XML tool call extraction from streaming ---

  test("extracts XML tool call from streamed text and emits as native tool_calls", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({
              event: "on_message_delta",
              data: { delta: { content: [{ type: "text", text: '<heelcode_tool_call>{"name":"glob","arguments":{"pattern":"*.ts"}}</heelcode_tool_call>', index: 0 }] } },
            })}\n\n`,
          ),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const output = await readStream(transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1"))
    expect(output).toContain('"tool_calls"')
    expect(output).toContain('"name":"glob"')
    expect(output).toContain('"finish_reason":"tool_calls"')
    expect(output).not.toContain("heelcode_tool_call")
  })

  test("streams text content before a tool call without buffering pre-tag text", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({
              event: "on_message_delta",
              data: { delta: { content: [{ type: "text", text: 'Hello world<heelcode_tool_call>{"name":"glob","arguments":{"pattern":"*.ts"}}</heelcode_tool_call>', index: 0 }] } },
            })}\n\n`,
          ),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const output = await readStream(transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1"))
    expect(output).toContain('"content":"Hello world"')
    expect(output).toContain('"tool_calls"')
    expect(output).toContain('"name":"glob"')
    expect(output).toContain('"finish_reason":"tool_calls"')
  })

  test("extracts XML tool call even when JSON has trailing garbage braces", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({
              event: "on_message_delta",
              data: { delta: { content: [{ type: "text", text: '<heelcode_tool_call>{"name":"glob","arguments":{"pattern":"*.ts"}}}}</heelcode_tool_call>', index: 0 }] } },
            })}\n\n`,
          ),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const output = await readStream(transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1"))
    expect(output).toContain('"tool_calls"')
    expect(output).toContain('"name":"glob"')
    expect(output).toContain('"finish_reason":"tool_calls"')
    expect(output).not.toContain("heelcode_tool_call")
  })

  test("falls back to plain text when XML is malformed", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({
              event: "on_message_delta",
              data: { delta: { content: [{ type: "text", text: "<heelcode_tool_call>not-valid-json</heelcode_tool_call>", index: 0 }] } },
            })}\n\n`,
          ),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const output = await readStream(transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1"))
    expect(output).not.toContain('"tool_calls"')
    expect(output).toContain('"finish_reason":"stop"')
  })

  // --- promptLabJSONToText edge cases ---

  test("promptLabJSONToText extracts text from first choices entry with content", () => {
    expect(promptLabJSONToText({ choices: [{ message: { content: "actual content" } }, { message: {} }, {}] })).toBe(
      "actual content",
    )
    expect(promptLabJSONToText({ choices: [{ message: {} }, {}] })).toBe("")
  })

  // --- promptLabTemperature ---

  test("promptLabTemperature returns undefined for non-default temperatures on bedrock Claude", () => {
    const bedrockClaude = { endpoint: "bedrock", model: "us.anthropic.claude-sonnet-4-6", openAIModelID: "promptlab/bedrock/us.anthropic.claude-sonnet-4-6" }
    expect(promptLabTemperature(0, bedrockClaude)).toBeUndefined()
    expect(promptLabTemperature(0.5, bedrockClaude)).toBeUndefined()
    // temperature=1 (default) is allowed through
    expect(promptLabTemperature(1, bedrockClaude)).toBe(1)
    // undefined passes through
    expect(promptLabTemperature(undefined, bedrockClaude)).toBeUndefined()
    // Non-bedrock endpoints are unaffected
    expect(promptLabTemperature(0, { endpoint: "openAI", model: "gpt-4.1", openAIModelID: "promptlab/openAI/gpt-4.1" })).toBe(0)
  })

  // --- openAINonStreamingResponse ---

  test("openAINonStreamingResponse returns correct OpenAI chat completion format", () => {
    const resp = openAINonStreamingResponse({ model: "gpt-4.1", content: "test content" })
    expect(resp.object).toBe("chat.completion")
    expect(resp.model).toBe("gpt-4.1")
    expect(resp.choices[0].message.content).toBe("test content")
    expect(resp.choices[0].finish_reason).toBe("stop")
    expect(typeof resp.id).toBe("string")
    expect(typeof resp.created).toBe("number")
    // Custom finishReason
    const resp2 = openAINonStreamingResponse({ model: "gpt-4.1", content: "x", finishReason: "tool_calls" })
    expect(resp2.choices[0].finish_reason).toBe("tool_calls")
  })

  // --- lastUserText ---

  test("lastUserText returns last user message text", () => {
    expect(
      lastUserText([
        { role: "system", content: "Start" },
        { role: "user", content: "User prompt" },
        { role: "assistant", content: "Reply" },
      ]),
    ).toBe("User prompt")
  })

  test("lastUserText falls back to full serialized messages if no user message", () => {
    const result = lastUserText([{ role: "system", content: "Start" }, { role: "assistant", content: "Reply" }])
    expect(result).toContain("system: Start")
  })

  // --- openAIToolCallStream ---

  test("openAIToolCallStream produces OpenAI-compliant SSE with tool call and done", async () => {
    const stream = openAIToolCallStream("gpt-4.1", {
      index: 0,
      id: "call_fn",
      type: "function",
      function: { name: "echo", arguments: '{"text":"hi"}' },
    })
    const out = await readStream(stream)
    expect(out).toContain('"tool_calls"')
    expect(out).toContain('"name":"echo"')
    expect(out).toContain('"finish_reason":"tool_calls"')
    expect(out).toContain("data: [DONE]")
  })

  // --- messageContentToText ---

  test("messageContentToText converts string content directly", () => {
    expect(messageContentToText("Hello")).toBe("Hello")
    expect(messageContentToText("")).toBe("")
    expect(messageContentToText(null as never)).toBe("")
  })

  test("messageContentToText joins multi-part content blocks", () => {
    expect(
      messageContentToText([
        { type: "text", text: "Part A" },
        { type: "text", text: "Part B" },
      ]),
    ).toBe("Part A\nPart B")
    expect(messageContentToText([{ type: "image_url", image_url: { url: "http://example.com/img.png" } }])).toBe(
      "[image]",
    )
  })

  // --- selectionFromRequest ---

  test("selectionFromRequest parses promptlab model ID into endpoint and model", () => {
    const sel = selectionFromRequest({
      model: "promptlab/azureOpenAI/gpt-4.1",
      messages: [],
      stream: true,
    })
    expect(sel.endpoint).toBe("azureOpenAI")
    expect(sel.model).toBe("gpt-4.1")
    expect(sel.openAIModelID).toBe("promptlab/azureOpenAI/gpt-4.1")
  })

  test("selectionFromRequest throws on invalid model ID", () => {
    expect(() =>
      selectionFromRequest({ model: "not-a-promptlab-model", messages: [], stream: true }),
    ).toThrow(/Expected PromptLab model id/)
  })

  // --- openAIChunk ---

  test("openAIChunk builds a streaming delta with content", () => {
    const chunk = openAIChunk({ id: "chatcmpl-1", model: "gpt-4.1", content: "Hello" })
    expect(chunk.object).toBe("chat.completion.chunk")
    expect(chunk.id).toBe("chatcmpl-1")
    expect(chunk.model).toBe("gpt-4.1")
    expect(chunk.choices[0].delta.content).toBe("Hello")
    expect(chunk.choices[0].finish_reason).toBeNull()
  })

  test("openAIChunk builds a finish chunk with finishReason and no delta content", () => {
    const chunk = openAIChunk({ id: "chatcmpl-1", model: "gpt-4.1", finishReason: "stop" })
    expect(chunk.choices[0].finish_reason).toBe("stop")
    expect(chunk.choices[0].delta).not.toHaveProperty("content")
  })

  // --- promptLabStreamToText ---

  test("promptLabStreamToText collects text from on_message_delta events", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({ event: "on_message_delta", data: { delta: { content: [{ type: "text", text: "Hello ", index: 0 }] } } })}\n\n`,
          ),
        )
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({ event: "on_message_delta", data: { delta: { content: [{ type: "text", text: "world", index: 0 }] } } })}\n\n`,
          ),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true, responseMessage: { text: "Hello world" } })}\n\n`))
        controller.close()
      },
    })
    const text = await promptLabStreamToText(source)
    expect(text).toContain("Hello ")
    expect(text).toContain("world")
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

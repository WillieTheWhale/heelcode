import { describe, expect, test } from "bun:test"
import {
  buildPromptLabPayload,
  messagesToPromptText,
  preflightToolCallFromRequest,
  promptLabEventToDelta,
  toolInstructionFromRequest,
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
    const payloadText = String(payload.text)
    expect(payloadText).toContain("Heelcode local opencode tools are available")
    expect(payloadText).toContain("Use the list tool")
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

  test("exposes synthetic tool instructions without sending OpenAI tools to PromptLab", () => {
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

    expect(payload.text).toContain("Heelcode local opencode tools are available")
    expect(payload.text).toContain("Reply with exactly: HEELCODE_OK")
    expect(payload).not.toHaveProperty("messages")
    expect(payload).not.toHaveProperty("tools")
    expect(payload).not.toHaveProperty("tool_choice")
  })

  test("keeps the synthetic tool loop active after opencode returns tool results", () => {
    const instruction = toolInstructionFromRequest({
      model: "promptlab/openAI/gpt-4.1",
      messages: [
        { role: "user", content: "Fix the failing tests." },
        { role: "tool", content: "test failure output", tool_call_id: "call_1" },
      ],
      tools: [{ type: "function", function: { name: "read", parameters: {} } }],
      tool_choice: "auto",
    })

    expect(instruction).toContain("call exactly one next tool")
    expect(instruction).toContain("Do not ask the user what to do next")
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

  test("converts prose tool intent into OpenAI-compatible tool calls for opencode", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(`data: ${JSON.stringify({ message: "I should use the read tool for package.json." })}\n\n`),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const stream = transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1", {
      tools: [{ type: "function", function: { name: "read", parameters: {} } }],
    })
    const output = await readStream(stream)
    expect(output).toContain('"tool_calls"')
    expect(output).toContain('"name":"read"')
    expect(output).toContain('"arguments":"{\\"filePath\\":\\"package.json\\"}"')
    expect(output).toContain('"finish_reason":"tool_calls"')
  })

  test("cleans XML-like suffixes from prose path tool calls", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({ message: "I should use the read tool for path package.json</path>." })}\n\n`,
          ),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const stream = transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1", {
      tools: [{ type: "function", function: { name: "read", parameters: {} } }],
    })
    const output = await readStream(stream)
    expect(output).toContain('"tool_calls"')
    expect(output).toContain('"name":"read"')
    expect(output).toContain('"arguments":"{\\"filePath\\":\\"package.json\\"}"')
    expect(output).not.toContain("package.json</path>")
  })

  test("extracts definition targets for prose grep calls", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({
              message: "I should use grep to find where toolInstructionFromRequest is defined.",
            })}\n\n`,
          ),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const stream = transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1", {
      tools: [{ type: "function", function: { name: "grep", parameters: {} } }],
    })
    const output = await readStream(stream)
    expect(output).toContain('"tool_calls"')
    expect(output).toContain('"name":"grep"')
    expect(output).toContain('"arguments":"{\\"pattern\\":\\"toolInstructionFromRequest\\"}"')
  })

  test("does not infer read calls from vague file prose", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({ message: "I should read the matching file before answering." })}\n\n`,
          ),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const stream = transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1", {
      tools: [{ type: "function", function: { name: "read", parameters: {} } }],
    })
    const output = await readStream(stream)
    expect(output).not.toContain('"tool_calls"')
    expect(output).toContain("I should read the matching file before answering.")
  })

  test("does not infer file reads from XML task result tag fragments", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ message: "I should read /task_result>." })}\n\n`))
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const stream = transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1", {
      tools: [{ type: "function", function: { name: "read", parameters: {} } }],
    })
    const output = await readStream(stream)
    expect(output).not.toContain('"tool_calls"')
    expect(output).toContain("I should read /task_result>.")
  })

  test("infers safe inspection tools from prose when PromptLab skips XML", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ message: "I should inspect package.json." })}\n\n`))
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const stream = transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1", {
      tools: [
        { type: "function", function: { name: "glob", parameters: {} } },
        { type: "function", function: { name: "read", parameters: {} } },
      ],
    })
    const output = await readStream(stream)
    expect(output).toContain('"tool_calls"')
    expect(output).toContain('"name":"read"')
    expect(output).toContain('"arguments":"{\\"filePath\\":\\"package.json\\"}"')
  })

  test("converts explicit subagent prose into a local task tool call", async () => {
    const source = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(
          encoder.encode(
            `data: ${JSON.stringify({
              message:
                'I should use the task tool with subagent_type explore and prompt "Inspect package.json and report the package name."',
            })}\n\n`,
          ),
        )
        controller.enqueue(encoder.encode(`data: ${JSON.stringify({ final: true })}\n\n`))
        controller.close()
      },
    })

    const stream = transformPromptLabSSEToOpenAI(source, "promptlab/openAI/gpt-4.1", {
      tools: [{ type: "function", function: { name: "task", parameters: {} } }],
    })
    const output = await readStream(stream)
    expect(output).toContain('"tool_calls"')
    expect(output).toContain('"name":"task"')
    expect(output).toContain('\\"subagent_type\\":\\"explore\\"')
    expect(output).toContain('\\"prompt\\":\\"Inspect package.json and report the package name.\\"')
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

  test("preflights explicitly named task tools before tools mentioned inside the task prompt", () => {
    const call = preflightToolCallFromRequest({
      model: "promptlab/openAI/gpt-4.1",
      stream: true,
      messages: [
        {
          role: "user",
          content:
            'Use the task tool with subagent_type explore and prompt "Read package.json and report only the package name."',
        },
      ],
      tools: [
        { type: "function", function: { name: "read", parameters: {} } },
        { type: "function", function: { name: "task", parameters: {} } },
      ],
    })

    expect(call?.function?.name).toBe("task")
    expect(call?.function?.arguments).toContain('"subagent_type":"explore"')
    expect(call?.function?.arguments).toContain('"prompt":"Read package.json and report only the package name."')
  })

  test("preflights task tools from JSON-quoted CLI prompts", () => {
    const call = preflightToolCallFromRequest({
      model: "promptlab/openAI/gpt-4.1",
      stream: true,
      messages: [
        {
          role: "user",
          content: JSON.stringify(
            'Use the task tool with subagent_type explore and prompt "Read package.json and report only the package name."',
          ),
        },
      ],
      tools: [
        { type: "function", function: { name: "glob", parameters: {} } },
        { type: "function", function: { name: "read", parameters: {} } },
        { type: "function", function: { name: "task", parameters: {} } },
      ],
    })

    expect(call?.function?.name).toBe("task")
    expect(call?.function?.arguments).toContain('"subagent_type":"explore"')
    expect(call?.function?.arguments).toContain('"prompt":"Read package.json and report only the package name."')
  })

  test("preflights explicit follow-up tools from previous tool output", () => {
    const call = preflightToolCallFromRequest({
      model: "promptlab/openAI/gpt-4.1",
      stream: true,
      messages: [
        {
          role: "user",
          content: "Use grep to find where toolInstructionFromRequest is defined, then read the matching file.",
        },
        {
          role: "tool",
          tool_call_id: "call_grep",
          content:
            'Found 4 matches\n\n/Users/example/heelcode/packages/promptlab/src/server.ts:\n  Line 12:   toolInstructionFromRequest,\n\n/Users/example/heelcode/packages/promptlab/src/openai.test.ts:\n  Line 91:     const instruction = toolInstructionFromRequest({\n  Line 473:     "Line 85: export function toolInstructionFromRequest",\n\n/Users/example/heelcode/packages/promptlab/src/openai.ts:\n  Line 85: export function toolInstructionFromRequest',
        },
      ],
      tools: [
        { type: "function", function: { name: "grep", parameters: {} } },
        { type: "function", function: { name: "read", parameters: {} } },
      ],
    })

    expect(call?.function?.name).toBe("read")
    expect(call?.function?.arguments).toBe('{"filePath":"/Users/example/heelcode/packages/promptlab/src/openai.ts"}')
  })

  test("does not preflight sequenced follow-up tools without concrete arguments", () => {
    const call = preflightToolCallFromRequest({
      model: "promptlab/openAI/gpt-4.1",
      stream: true,
      messages: [
        {
          role: "user",
          content: "Use grep to find where toolInstructionFromRequest is defined, then read the matching file.",
        },
        {
          role: "tool",
          tool_call_id: "call_grep",
          content: "No files found",
        },
      ],
      tools: [
        { type: "function", function: { name: "grep", parameters: {} } },
        { type: "function", function: { name: "read", parameters: {} } },
      ],
    })

    expect(call).toBeUndefined()
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

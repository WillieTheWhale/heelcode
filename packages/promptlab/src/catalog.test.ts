import { describe, expect, test } from "bun:test"
import { catalogMetrics, decodeOpenAIModelID, normalizeCatalog, openAIModelID, toOpenAIModels } from "./catalog"

describe("PromptLab catalog normalization", () => {
  test("maps endpoint keyed model lists", () => {
    const catalog = normalizeCatalog(
      {
        openAI: ["gpt-4.1", { id: "gpt-5-mini", name: "GPT-5 Mini" }],
        anthropic: { models: [{ model: "claude-sonnet-4-5", label: "Claude Sonnet 4.5" }] },
      },
      [
        { id: "openAI", name: "OpenAI" },
        { id: "anthropic", name: "Anthropic" },
      ],
    )

    expect(catalogMetrics(catalog)).toMatchObject({ endpointCount: 2, modelCount: 3, mappedModelCount: 3 })
    expect(toOpenAIModels(catalog).data.map((model) => model.id)).toContain("promptlab/openAI/gpt-4.1")
    expect(toOpenAIModels(catalog).data.map((model) => model.id)).toContain("promptlab/anthropic/claude-sonnet-4-5")
  })

  test("filters provider-key groups that are not configured PromptLab endpoints", () => {
    const catalog = normalizeCatalog(
      {
        openAI: ["gpt-5.4"],
        anthropic: ["claude-sonnet-4-6"],
        azureOpenAI: ["gpt-4.1"],
        google: ["gemini-2.5-flash"],
        bedrock: ["us.anthropic.claude-sonnet-4-6"],
        assistants: ["assistant-model"],
      },
      [
        { id: "azureOpenAI", name: "Azure OpenAI" },
        { id: "bedrock", name: "Bedrock" },
        { id: "google", name: "Google" },
        { id: "agents", name: "Agents" },
      ],
    )

    const ids = toOpenAIModels(catalog).data.map((model) => model.id)
    expect(ids).toContain("promptlab/azureOpenAI/gpt-4.1")
    expect(ids).toContain("promptlab/bedrock/us.anthropic.claude-sonnet-4-6")
    expect(ids).toContain("promptlab/google/gemini-2.5-flash")
    expect(ids).not.toContain("promptlab/openAI/gpt-5.4")
    expect(ids).not.toContain("promptlab/anthropic/claude-sonnet-4-6")
    expect(ids).not.toContain("promptlab/assistants/assistant-model")
  })

  test("round trips encoded model ids", () => {
    const id = openAIModelID("custom", "provider/model")
    expect(id).toBe("promptlab/custom/provider%2Fmodel")
    expect(decodeOpenAIModelID(id)).toEqual({
      openAIModelID: id,
      endpoint: "custom",
      model: "provider/model",
    })
  })
})

import { describe, expect, test } from "bun:test"
import { catalogMetrics, decodeOpenAIModelID, normalizeCatalog, openAIModelID, toOpenAIModels } from "./catalog"

describe("PromptLab catalog normalization", () => {
  test("maps endpoint keyed model lists", () => {
    const catalog = normalizeCatalog(
      {
        openAI: ["gpt-4.1", { id: "gpt-5-mini", name: "GPT-5 Mini" }],
        anthropic: { models: [{ model: "claude-sonnet-4-5", label: "Claude Sonnet 4.5" }] },
      },
      [{ id: "openAI", name: "OpenAI" }, { id: "anthropic", name: "Anthropic" }],
    )

    expect(catalogMetrics(catalog)).toMatchObject({ endpointCount: 2, modelCount: 3, mappedModelCount: 3 })
    expect(toOpenAIModels(catalog).data.map((model) => model.id)).toContain("promptlab/openAI/gpt-4.1")
    expect(toOpenAIModels(catalog).data.map((model) => model.id)).toContain("promptlab/anthropic/claude-sonnet-4-5")
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

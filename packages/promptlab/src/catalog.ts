import type { CatalogMetrics, ModelSelection, PromptLabCatalog, PromptLabEndpoint, PromptLabModel } from "./types"

const FALLBACK_ENDPOINT = "default"

export function openAIModelID(endpoint: string, model: string): string {
  return `promptlab/${encodeURIComponent(endpoint)}/${encodeURIComponent(model)}`
}

export function decodeOpenAIModelID(id: string): ModelSelection | undefined {
  const match = /^promptlab\/([^/]+)\/(.+)$/.exec(id)
  if (!match) return undefined
  return {
    openAIModelID: id,
    endpoint: decodeURIComponent(match[1]),
    model: decodeURIComponent(match[2]),
  }
}

export function normalizeCatalog(modelsRaw: unknown, endpointsRaw: unknown): PromptLabCatalog {
  const endpoints = normalizeEndpoints(endpointsRaw)
  const endpointIDs = new Set(endpoints.map((endpoint) => endpoint.id))
  const models = collectModels(modelsRaw)

  for (const endpoint of collectEndpointModels(endpointsRaw)) {
    if (!endpointIDs.has(endpoint.id)) {
      endpoints.push({ id: endpoint.id, name: endpoint.name })
      endpointIDs.add(endpoint.id)
    }
    models.push(...endpoint.models)
  }

  if (!endpoints.length) {
    for (const endpoint of new Set(models.map((model) => model.endpoint).filter(Boolean))) {
      endpoints.push({ id: endpoint, name: endpoint })
    }
  }

  if (!endpoints.length) endpoints.push({ id: FALLBACK_ENDPOINT, name: "PromptLab" })
  const defaultEndpoint = endpoints.length === 1 ? endpoints[0].id : FALLBACK_ENDPOINT

  const seen = new Set<string>()
  const normalizedModels = models
    .map((model) => ({
      ...model,
      endpoint: model.endpoint || defaultEndpoint,
      name: model.name || model.id,
    }))
    .filter((model) => {
      if (!model.id) return false
      const key = `${model.endpoint}/${model.id}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    .sort((a, b) => openAIModelID(a.endpoint, a.id).localeCompare(openAIModelID(b.endpoint, b.id)))

  return {
    endpoints: endpoints.sort((a, b) => a.id.localeCompare(b.id)),
    models: normalizedModels,
  }
}

export function catalogMetrics(catalog: PromptLabCatalog): CatalogMetrics {
  const counts = new Map<string, number>()
  for (const model of catalog.models) {
    const id = openAIModelID(model.endpoint, model.id)
    counts.set(id, (counts.get(id) ?? 0) + 1)
  }
  return {
    endpointCount: catalog.endpoints.length,
    modelCount: catalog.models.length,
    mappedModelCount: catalog.models.filter((model) => model.endpoint && model.id).length,
    duplicateModelIDs: Array.from(counts.entries())
      .filter(([, count]) => count > 1)
      .map(([id]) => id),
  }
}

export function toOpenAIModels(catalog: PromptLabCatalog) {
  return {
    object: "list",
    data: catalog.models.map((model) => ({
      id: openAIModelID(model.endpoint, model.id),
      object: "model",
      created: 0,
      owned_by: "promptlab",
      name: model.name,
      endpoint: model.endpoint,
    })),
  }
}

function normalizeEndpoints(raw: unknown): PromptLabEndpoint[] {
  if (Array.isArray(raw)) {
    return raw.flatMap((item) => parseEndpoint(item))
  }
  if (isRecord(raw)) {
    const direct = parseEndpoint(raw)
    if (direct.length) return direct
    return Object.entries(raw).flatMap(([key, value]) => {
      if (isRecord(value)) {
        return [{ id: stringValue(value.id) ?? stringValue(value.endpoint) ?? key, name: stringValue(value.name) ?? key, raw: value }]
      }
      return [{ id: key, name: key, raw: value }]
    })
  }
  return []
}

function parseEndpoint(raw: unknown): PromptLabEndpoint[] {
  if (typeof raw === "string") return [{ id: raw, name: raw }]
  if (!isRecord(raw)) return []
  const id = stringValue(raw.id) ?? stringValue(raw.endpoint) ?? stringValue(raw.key) ?? stringValue(raw.value)
  if (!id) return []
  return [{ id, name: stringValue(raw.name) ?? stringValue(raw.label) ?? id, raw }]
}

function collectEndpointModels(raw: unknown): Array<PromptLabEndpoint & { models: PromptLabModel[] }> {
  if (!Array.isArray(raw)) return []
  return raw.flatMap((endpoint) => {
    if (!isRecord(endpoint)) return []
    const parsed = parseEndpoint(endpoint)[0]
    if (!parsed) return []
    const models = collectModels(endpoint.models ?? endpoint.modelOptions ?? endpoint.availableModels, parsed.id)
    return [{ ...parsed, models }]
  })
}

function collectModels(raw: unknown, endpoint?: string): PromptLabModel[] {
  if (typeof raw === "string") return [makeModel(raw, raw, endpoint, raw)]
  if (Array.isArray(raw)) return raw.flatMap((item) => collectModels(item, endpoint))
  if (!isRecord(raw)) return []

  const direct = parseModel(raw, endpoint)
  if (direct) return [direct]

  return Object.entries(raw).flatMap(([key, value]) => {
    if (key === "models" || key === "modelOptions" || key === "availableModels") return collectModels(value, endpoint)
    const nextEndpoint = looksLikeModelList(value) ? key : endpoint
    return collectModels(value, nextEndpoint)
  })
}

function parseModel(raw: Record<string, unknown>, endpoint?: string): PromptLabModel | undefined {
  const id =
    stringValue(raw.model) ??
    stringValue(raw.modelId) ??
    stringValue(raw.modelID) ??
    stringValue(raw.id) ??
    stringValue(raw.value) ??
    stringValue(raw.key)
  if (!id) return undefined

  const rawEndpoint =
    stringValue(raw.endpoint) ??
    stringValue(raw.endpointId) ??
    stringValue(raw.endpointID) ??
    stringValue(raw.provider) ??
    endpoint
  return makeModel(id, stringValue(raw.name) ?? stringValue(raw.label) ?? id, rawEndpoint, raw)
}

function makeModel(id: string, name: string, endpoint: string | undefined, raw: unknown): PromptLabModel {
  return {
    id,
    name,
    endpoint: endpoint ?? "",
    raw,
  }
}

function looksLikeModelList(value: unknown): boolean {
  if (Array.isArray(value)) return true
  if (!isRecord(value)) return false
  return "models" in value || "modelOptions" in value || "availableModels" in value
}

function stringValue(input: unknown): string | undefined {
  return typeof input === "string" && input.trim() ? input.trim() : undefined
}

function isRecord(input: unknown): input is Record<string, unknown> {
  return typeof input === "object" && input !== null && !Array.isArray(input)
}

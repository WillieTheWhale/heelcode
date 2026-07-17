const ACTION_CONFORMANCE_RETRIES = 3

const ACTION =
  "check|read|inspect|look|open|locate|find|review|examine|search|prepare|update|edit|implement|run|test|fix|work|build|add|replace|create|verify|investigate|trace|install|start|continue"

export function isUnfinishedNarration(text: string) {
  const normalized = text.trim().replaceAll("’", "'")
  if (!normalized) return true
  return [
    new RegExp(`\\b(?:i'm|i am|i'll|i will|i need to|let me)\\s+(?:now\\s+)?(?:${ACTION})(?:ing)?\\b`, "i"),
    new RegExp(`(?:^|[.!?]\\s+)(?:now\\s+)?(?:${ACTION})(?:ing)\\b`, "i"),
    /\b(?:i've|i have)\s+(?:located|found|identified)\b[^.!?]*(?:\bnext\b|\bso i can\b)/i,
  ].some((pattern) => pattern.test(normalized))
}

export function retryMessage(attempt: number, problem = "The preceding response only narrated unfinished work") {
  return `HEELCODE ACTION CONFORMANCE RETRY ${attempt}/${ACTION_CONFORMANCE_RETRIES}: ${problem}. Continue the same user task now. Your next response must contain exactly one typed HeelCode action that performs the next step (for example read, glob, grep, bash, edit, or write), not a description of an action. Do not ask the user to continue and do not batch multiple actions. If the task is genuinely complete, return a concrete final answer instead.`
}

export function canRetry(attempt: number) {
  return attempt < ACTION_CONFORMANCE_RETRIES
}

export function conformanceError(error: unknown) {
  if (!record(error) || !record(error.data) || typeof error.data.message !== "string") return undefined
  return /^(?:Visible response (?:contained multiple HeelCode actions|began as JSON but was malformed)|Structured action (?:must contain exactly type, name, and arguments|name must be a nonempty string|arguments must be an object))$/.test(
    error.data.message,
  )
    ? error.data.message
    : undefined
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null
}

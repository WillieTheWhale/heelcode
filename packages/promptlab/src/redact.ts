const SECRET_HEADER_NAMES = new Set([
  "authorization",
  "cookie",
  "set-cookie",
  "x-csrf-token",
  "x-xsrf-token",
  "x-auth-token",
  "proxy-authorization",
])

const SECRET_KEY_PATTERN =
  /(^|_|-)(password|passwd|pwd|secret|token|cookie|authorization|auth|session|csrf|xsrf|credential|key)($|_|-)/i

const PRIVATE_TEXT_KEYS = new Set([
  "email",
  "name",
  "displayname",
  "display_name",
  "username",
  "onyen",
  "prompt",
  "text",
  "content",
])

const BEARER_PATTERN = /\bBearer\s+[A-Za-z0-9._~+/=-]+/gi
const BASIC_PATTERN = /\bBasic\s+[A-Za-z0-9._~+/=-]+/gi
const COOKIE_PATTERN = /\b([A-Za-z0-9_.-]+)=([^;\s]+)/g

export const REDACTED = "[REDACTED]"

export function redactText(input: string): string {
  return input
    .replace(BEARER_PATTERN, `Bearer ${REDACTED}`)
    .replace(BASIC_PATTERN, `Basic ${REDACTED}`)
    .replace(COOKIE_PATTERN, (_match, key: string) => `${key}=${REDACTED}`)
}

export function redactHeaders(input: Headers | Record<string, string | string[] | undefined>): Record<string, string> {
  const headers: Record<string, string> = {}
  const entries =
    input instanceof Headers
      ? Array.from(input.entries())
      : Object.entries(input).flatMap(([key, value]) => {
          if (value === undefined) return []
          return [[key, Array.isArray(value) ? value.join("; ") : value] as const]
        })

  for (const [key, value] of entries) {
    headers[key] = SECRET_HEADER_NAMES.has(key.toLowerCase()) ? REDACTED : redactText(value)
  }
  return headers
}

export function redactJSON<T>(input: T): T {
  return redactValue(input, new WeakSet()) as T
}

function redactValue(input: unknown, seen: WeakSet<object>): unknown {
  if (typeof input === "string") return redactText(input)
  if (typeof input !== "object" || input === null) return input

  if (seen.has(input)) return "[Circular]"
  seen.add(input)

  if (Array.isArray(input)) return input.map((item) => redactValue(item, seen))

  const output: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(input)) {
    const normalized = key.toLowerCase()
    if (SECRET_KEY_PATTERN.test(normalized) || PRIVATE_TEXT_KEYS.has(normalized)) {
      output[key] = REDACTED
      continue
    }
    output[key] = redactValue(value, seen)
  }
  return output
}

export function redactError(error: unknown): string {
  if (error instanceof Error) return redactText(error.message)
  return redactText(String(error))
}

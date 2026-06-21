import { describe, expect, test } from "bun:test"
import { REDACTED, redactHeaders, redactJSON, redactText } from "./redact"

describe("redaction", () => {
  test("redacts bearer tokens and cookies in text", () => {
    expect(redactText("Authorization: Bearer abc.def.ghi; promptlab.sid=secret")).toContain(`Bearer ${REDACTED}`)
    expect(redactText("promptlab.sid=secret")).toBe(`promptlab.sid=${REDACTED}`)
  })

  test("redacts sensitive headers", () => {
    expect(redactHeaders({ authorization: "Bearer abc", cookie: "sid=abc", accept: "application/json" })).toEqual({
      authorization: REDACTED,
      cookie: REDACTED,
      accept: "application/json",
    })
  })

  test("redacts private json fields", () => {
    expect(
      redactJSON({
        token: "abc",
        user: { email: "student@example.edu", username: "abc123" },
        request: { content: "private prompt" },
      }),
    ).toEqual({
      token: REDACTED,
      user: { email: REDACTED, username: REDACTED },
      request: { content: REDACTED },
    })
  })
})

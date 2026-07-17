import { describe, expect, test } from "bun:test"
import { updatedCookieHeader } from "./cookie"

describe("PromptLab session cookies", () => {
  test("merges rotated refresh cookies while preserving unrelated browser cookies", () => {
    const headers = new Headers()
    headers.append("set-cookie", "refresh=fresh; Path=/; HttpOnly; SameSite=Lax")

    expect(updatedCookieHeader("refresh=old; stable=keep", headers)).toBe("refresh=fresh; stable=keep")
  })

  test("removes explicitly expired cookies", () => {
    const headers = new Headers()
    headers.append("set-cookie", "refresh=; Path=/; Max-Age=0")

    expect(updatedCookieHeader("refresh=old; stable=keep", headers)).toBe("stable=keep")
  })
})

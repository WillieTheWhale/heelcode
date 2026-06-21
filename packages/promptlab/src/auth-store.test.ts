import { describe, expect, test } from "bun:test"
import { configWithStoredSession, loadOnyenCredentials } from "./auth-store"

describe("auth store", () => {
  test("uses runtime PromptLab session environment before persistent storage", async () => {
    await expect(
      configWithStoredSession({
        PROMPTLAB_BASE_URL: "https://promptlab.example",
        PROMPTLAB_BEARER_TOKEN: "runtime-token",
        PROMPTLAB_COOKIE: "runtime-cookie",
      }),
    ).resolves.toEqual({
      baseURL: "https://promptlab.example",
      bearerToken: "runtime-token",
      cookie: "runtime-cookie",
    })
  })

  test("uses runtime ONYEN credentials without persistent storage", async () => {
    await expect(
      loadOnyenCredentials({
        PROMPTLAB_ONYEN_USERNAME: "abc123",
        PROMPTLAB_ONYEN_PASSWORD: "password",
      }),
    ).resolves.toEqual({
      username: "abc123",
      password: "password",
    })
  })
})

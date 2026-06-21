import { getSecret, setSecret, deleteSecret, hasSecret } from "./keychain"
import type { PromptLabConfig } from "./types"

export type StoredOnyenCredentials = {
  username: string
  password: string
}

export async function configWithStoredSession(env: Record<string, string | undefined> = process.env): Promise<PromptLabConfig> {
  return {
    baseURL: env.PROMPTLAB_BASE_URL ?? "https://promptlab.lib.unc.edu",
    bearerToken: env.PROMPTLAB_BEARER_TOKEN ?? (await getSecret("promptlab-bearer-token").catch(() => undefined)),
    cookie: env.PROMPTLAB_COOKIE ?? (await getSecret("promptlab-cookie").catch(() => undefined)),
  }
}

export async function saveOnyenCredentials(input: StoredOnyenCredentials): Promise<void> {
  await setSecret("onyen-username", input.username)
  await setSecret("onyen-password", input.password)
}

export async function loadOnyenCredentials(
  env: Record<string, string | undefined> = process.env,
): Promise<StoredOnyenCredentials | undefined> {
  const username = env.PROMPTLAB_ONYEN_USERNAME ?? (await getSecret("onyen-username").catch(() => undefined))
  const password = env.PROMPTLAB_ONYEN_PASSWORD ?? (await getSecret("onyen-password").catch(() => undefined))
  if (!username || !password) return undefined
  return { username, password }
}

export async function deleteOnyenCredentials(): Promise<void> {
  await Promise.all([deleteSecret("onyen-username").catch(() => {}), deleteSecret("onyen-password").catch(() => {})])
}

export async function savePromptLabSession(input: { bearerToken?: string; cookie?: string }): Promise<void> {
  if (input.bearerToken) await setSecret("promptlab-bearer-token", input.bearerToken)
  if (input.cookie) await setSecret("promptlab-cookie", input.cookie)
}

export async function deletePromptLabSession(): Promise<void> {
  await Promise.all([
    deleteSecret("promptlab-bearer-token").catch(() => {}),
    deleteSecret("promptlab-cookie").catch(() => {}),
  ])
}

export async function credentialStatus() {
  const [username, password, bearerToken, cookie] = await Promise.all([
    hasSecret("onyen-username").catch(() => false),
    hasSecret("onyen-password").catch(() => false),
    hasSecret("promptlab-bearer-token").catch(() => false),
    hasSecret("promptlab-cookie").catch(() => false),
  ])
  return {
    onyenUsername: username,
    onyenPassword: password,
    promptLabBearerToken: bearerToken,
    promptLabCookie: cookie,
  }
}

import { copyFile, mkdtemp, readdir, rm } from "node:fs/promises"
import { tmpdir, homedir } from "node:os"
import { basename, join } from "node:path"
import { createDecipheriv, createHash, pbkdf2Sync } from "node:crypto"
import { savePromptLabSession } from "./auth-store"
import { redactJSON } from "./redact"

const storeSession = process.argv.includes("--store-session")
const promptLabURL = "https://promptlab.lib.unc.edu/c/new"
const promptLabOrigin = "https://promptlab.lib.unc.edu"
const promptLabHost = "promptlab.lib.unc.edu"
const pollMs = Number.parseInt(process.env.HEELCODE_PROMPTLAB_CAPTURE_POLL_MS ?? "250", 10)
const waitMs = Number.parseInt(process.env.HEELCODE_PROMPTLAB_CAPTURE_WAIT_MS ?? "180000", 10)
const chromeRoot =
  process.env.HEELCODE_CHROME_USER_DATA_DIR ?? join(homedir(), "Library", "Application Support", "Google", "Chrome")

type ChromeCookie = {
  hostKey: string
  name: string
  value: string
  path: string
  expiresUTC: number
  isSecure: boolean
  isHttpOnly: boolean
  profile: string
}

type RefreshResult = {
  profile: string
  cookie: string
  token: string
  capture: Record<string, unknown>
}

await openInRealChrome(promptLabURL)

let exitCode = 0
try {
  const result = await waitForPromptLabSession()
  if (storeSession) await savePromptLabSession({ bearerToken: result.token, cookie: result.cookie })

  const output = join(tmpdir(), "heelcode-promptlab-capture.json")
  await Bun.write(
    output,
    JSON.stringify(
      redactJSON({
        ...result.capture,
        profile: result.profile,
        cookie: result.cookie,
        token: result.token,
        tokenFound: true,
        sessionStored: storeSession,
      }),
      null,
      2,
    ),
  )
  console.log(output)
} catch (error) {
  exitCode = 1
  console.error(error instanceof Error ? (error.stack ?? error.message) : String(error))
} finally {
  console.error("PromptLab is open in your normal Chrome profile. No Chrome tabs or profiles were closed.")
  process.exit(exitCode)
}

async function waitForPromptLabSession() {
  const deadline = Date.now() + waitMs
  let announced = false
  while (Date.now() < deadline) {
    const result = await captureFromRealChromeProfiles()
    if (result) return result
    if (!announced) {
      console.error("Waiting for PromptLab login in your normal Chrome profile.")
      announced = true
    }
    await delay(pollMs)
  }
  throw new Error("Timed out waiting for an authenticated PromptLab session in the normal Chrome profile.")
}

async function captureFromRealChromeProfiles(): Promise<RefreshResult | undefined> {
  const profiles = await chromeProfiles()
  const secret = await chromeSafeStorageSecret()
  for (const profile of profiles) {
    const cookies = await readChromeCookies(profile.path, secret).catch(() => [])
    const promptLabCookies = cookies.filter((cookie) => cookieAppliesTo(cookie, promptLabHost))
    if (!promptLabCookies.length) continue
    const cookie = cookieHeader(promptLabCookies)
    const token = await refreshToken(cookie)
    if (!token) continue
    const headers = { Authorization: `Bearer ${token}`, Cookie: cookie }
    const [config, endpoints, models, user, active] = await Promise.all([
      getPromptLab("/api/config", { Cookie: cookie }),
      getPromptLab("/api/endpoints", headers),
      getPromptLab("/api/models", headers),
      getPromptLab("/api/user", headers),
      getPromptLab("/api/agents/chat/active", headers),
    ])
    return {
      profile: profile.name,
      cookie,
      token,
      capture: {
        at: new Date().toISOString(),
        href: promptLabURL,
        config,
        endpoints,
        models,
        user,
        active,
      },
    }
  }
  return undefined
}

async function refreshToken(cookie: string) {
  const response = await fetch(`${promptLabOrigin}/api/auth/refresh`, {
    method: "POST",
    headers: { Cookie: cookie },
  }).catch(() => undefined)
  if (!response?.ok) return undefined
  const body = await response.text().catch(() => "")
  return tokenFromValue(body)
}

async function getPromptLab(path: string, headers: Record<string, string>) {
  try {
    const response = await fetch(`${promptLabOrigin}${path}`, { headers })
    const contentType = response.headers.get("content-type") ?? ""
    const body = contentType.includes("application/json")
      ? await response.json().catch(() => undefined)
      : await response.text()
    return { status: response.status, ok: response.ok, body }
  } catch (error) {
    return { status: 0, ok: false, error: String(error) }
  }
}

async function chromeProfiles() {
  const entries = await readdir(chromeRoot, { withFileTypes: true }).catch(() => [])
  return entries
    .filter((entry) => entry.isDirectory())
    .filter((entry) => entry.name === "Default" || entry.name.startsWith("Profile "))
    .sort((a, b) => chromeProfileRank(a.name) - chromeProfileRank(b.name) || a.name.localeCompare(b.name))
    .map((entry) => ({
      name: entry.name,
      path: join(chromeRoot, entry.name),
    }))
}

function chromeProfileRank(name: string) {
  return name === "Default" ? 0 : 1
}

async function readChromeCookies(profilePath: string, secret: string): Promise<ChromeCookie[]> {
  const cookiesDB = join(profilePath, "Cookies")
  const tempDir = await mkdtemp(join(tmpdir(), "heelcode-chrome-cookies-"))
  const tempDB = join(tempDir, "Cookies")
  try {
    await copyFile(cookiesDB, tempDB)
    await copyFile(`${cookiesDB}-wal`, `${tempDB}-wal`).catch(() => {})
    await copyFile(`${cookiesDB}-shm`, `${tempDB}-shm`).catch(() => {})
    const version = await chromeCookieDBVersion(tempDB)
    const output = await sqlite(
      tempDB,
      `SELECT host_key, name, value, hex(encrypted_value), path, expires_utc, is_secure, is_httponly FROM cookies WHERE host_key LIKE '%${promptLabHost.replaceAll("'", "''")}';`,
    )
    return output
      .split("\n")
      .filter(Boolean)
      .map((line) => parseCookieLine(line, profilePath, secret, version))
      .filter((cookie): cookie is ChromeCookie => Boolean(cookie))
      .filter((cookie) => !isExpiredChromeCookie(cookie))
  } finally {
    await rm(tempDir, { recursive: true, force: true }).catch(() => {})
  }
}

async function chromeCookieDBVersion(db: string) {
  const output = await sqlite(db, "SELECT value FROM meta WHERE key='version';").catch(() => "")
  return Number.parseInt(output.trim(), 10)
}

function parseCookieLine(line: string, profilePath: string, secret: string, version: number) {
  const [hostKey, name, plainValue, encryptedHex, path, expiresUTC, isSecure, isHttpOnly] = line.split("\t")
  if (!hostKey || !name) return undefined
  const value = plainValue || decryptChromeCookie(hostKey, encryptedHex ?? "", secret, version)
  if (!value) return undefined
  return {
    hostKey,
    name,
    value,
    path: path || "/",
    expiresUTC: Number.parseInt(expiresUTC ?? "0", 10) || 0,
    isSecure: isSecure === "1",
    isHttpOnly: isHttpOnly === "1",
    profile: basename(profilePath),
  }
}

function decryptChromeCookie(hostKey: string, encryptedHex: string, secret: string, version: number) {
  if (!encryptedHex) return ""
  const encrypted = Buffer.from(encryptedHex, "hex")
  if (encrypted.subarray(0, 3).toString() !== "v10") return ""
  const key = pbkdf2Sync(secret, "saltysalt", 1003, 16, "sha1")
  const iv = Buffer.alloc(16, " ")
  try {
    const decipher = createDecipheriv("aes-128-cbc", key, iv)
    let decrypted = Buffer.concat([decipher.update(encrypted.subarray(3)), decipher.final()])
    const hostDigest = createHash("sha256").update(hostKey).digest()
    if (version >= 24 && decrypted.subarray(0, 32).equals(hostDigest)) decrypted = decrypted.subarray(32)
    return decrypted.toString("utf8")
  } catch {
    return ""
  }
}

async function chromeSafeStorageSecret() {
  const result = Bun.spawn(
    ["/usr/bin/security", "find-generic-password", "-a", "Chrome", "-s", "Chrome Safe Storage", "-w"],
    {
      stdout: "pipe",
      stderr: "pipe",
    },
  )
  const [stdout, stderr, code] = await Promise.all([
    new Response(result.stdout).text(),
    new Response(result.stderr).text(),
    result.exited,
  ])
  if (code !== 0 || !stdout.trim()) {
    throw new Error(`Unable to read Chrome Safe Storage key from macOS Keychain: ${stderr.trim()}`)
  }
  return stdout.trim()
}

async function sqlite(db: string, query: string) {
  const result = Bun.spawn(["sqlite3", "-separator", "\t", db, query], { stdout: "pipe", stderr: "pipe" })
  const [stdout, stderr, code] = await Promise.all([
    new Response(result.stdout).text(),
    new Response(result.stderr).text(),
    result.exited,
  ])
  if (code !== 0) throw new Error(stderr.trim() || `sqlite3 exited with ${code}`)
  return stdout
}

function cookieAppliesTo(cookie: ChromeCookie, host: string) {
  const cookieHost = cookie.hostKey.startsWith(".") ? cookie.hostKey.slice(1) : cookie.hostKey
  return host === cookieHost || host.endsWith(`.${cookieHost}`)
}

function cookieHeader(cookies: ChromeCookie[]) {
  return cookies
    .sort((a, b) => b.path.length - a.path.length)
    .map((cookie) => `${cookie.name}=${cookie.value}`)
    .join("; ")
}

function isExpiredChromeCookie(cookie: ChromeCookie) {
  if (!cookie.expiresUTC) return false
  return chromeTimeToUnixMs(cookie.expiresUTC) <= Date.now()
}

function chromeTimeToUnixMs(chromeTime: number) {
  return Math.floor(chromeTime / 1000 - 11_644_473_600_000)
}

function tokenFromValue(value: unknown): string | undefined {
  if (typeof value === "string") {
    if (looksLikeJWT(value)) return value
    try {
      return tokenFromValue(JSON.parse(value))
    } catch {
      return undefined
    }
  }
  if (!value || typeof value !== "object") return undefined
  for (const candidate of ["token", "accessToken", "access_token", "idToken", "id_token"]) {
    const nested = (value as Record<string, unknown>)[candidate]
    if (typeof nested === "string" && looksLikeJWT(nested)) return nested
  }
  return undefined
}

function looksLikeJWT(value: string) {
  return /^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(value)
}

async function openInRealChrome(url: string) {
  await Bun.spawn(["open", "-a", "Google Chrome", url], {
    stdout: "ignore",
    stderr: "ignore",
  }).exited.catch(() => {})
}

function delay(ms: number) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

import { chromium, type Page } from "@playwright/test"
import { mkdtemp } from "node:fs/promises"
import { join } from "node:path"
import { tmpdir } from "node:os"
import { redactJSON } from "./redact"

const username = process.env.PROMPTLAB_ONYEN_USERNAME
if (!username) {
  console.error("Set PROMPTLAB_ONYEN_USERNAME before running capture.")
  process.exit(1)
}

const password = await readPassword()
const userDataDir = await mkdtemp(join(tmpdir(), "heelcode-promptlab-chrome-"))
const context = await chromium.launchPersistentContext(userDataDir, {
  channel: "chrome",
  headless: false,
  args: ["--disable-blink-features=AutomationControlled"],
  viewport: { width: 1280, height: 900 },
})

try {
  const page = context.pages()[0] ?? (await context.newPage())
  await page.goto("https://promptlab.lib.unc.edu/c/new", { waitUntil: "domcontentloaded" })
  await page.waitForLoadState("networkidle").catch(() => {})
  const openedLogin = await clickText(page, ["Continue with ONYEN", "ONYEN", "Login", "Log in", "Sign in"], {
    optional: true,
  })
  if (!openedLogin) await page.goto("https://promptlab.lib.unc.edu/oauth/openid", { waitUntil: "domcontentloaded" })
  await page.waitForLoadState("domcontentloaded").catch(() => {})

  await fillFirst(
    page,
    [
      'input[name="username"]',
      'input[name="j_username"]',
      'input[name="UserName"]',
      'input[name="userid"]',
      'input[autocomplete="username"]',
      "#username",
      "#j_username",
      "#UserName",
      "#userid",
      'input[type="email"]',
      'input[type="text"]',
    ],
    username,
  )
  await clickText(page, ["Next", "Continue"], { optional: true })
  await choosePasswordSignIn(page)
  await fillFirst(page, ['input[name="password"]', "#password", 'input[type="password"]'], password)
  await page.keyboard.press("Enter").catch(() => {})
  await page.waitForLoadState("domcontentloaded", { timeout: 5_000 }).catch(() => {})
  if (!page.url().includes("promptlab.lib.unc.edu")) {
    await clickText(page, ["Sign in", "Log in", "Login", "Submit", "Continue"])
  }

  await page.waitForURL(/promptlab\.lib\.unc\.edu/, { timeout: 120_000 }).catch(() => {})
  if (!page.url().includes("promptlab.lib.unc.edu")) {
    await debugPage(page, "not-authenticated")
    throw new Error(`PromptLab login did not complete; current URL is ${page.url()}`)
  }
  await page.waitForLoadState("networkidle", { timeout: 30_000 }).catch(() => {})
  if (!page.url().includes("promptlab.lib.unc.edu")) {
    await debugPage(page, "not-authenticated")
    throw new Error(`PromptLab login did not remain on PromptLab; current URL is ${page.url()}`)
  }

  const capture = await page.evaluate(async () => {
    const token = await getToken()
    const headers: Record<string, string> = token ? { Authorization: `Bearer ${token}` } : {}
    const [config, endpoints, models, user, active] = await Promise.all([
      get("/api/config", {}),
      get("/api/endpoints", headers),
      get("/api/models", headers),
      get("/api/user", headers),
      get("/api/agents/chat/active", headers),
    ])
    return {
      at: new Date().toISOString(),
      href: String((globalThis as { location?: { href?: string } }).location?.href ?? ""),
      tokenFound: Boolean(token),
      storageKeys: {
        localStorage: Object.keys(localStorage).sort(),
        sessionStorage: Object.keys(sessionStorage).sort(),
      },
      config,
      endpoints,
      models,
      user,
      active,
    }

    async function get(path: string, headers: Record<string, string>) {
      try {
        const response = await fetch(path, {
          credentials: "include",
          headers,
        })
        const contentType = response.headers.get("content-type") ?? ""
        const body = contentType.includes("application/json")
          ? await response.json().catch(() => undefined)
          : await response.text()
        return { status: response.status, ok: response.ok, body }
      } catch (error) {
        return { status: 0, ok: false, error: String(error) }
      }
    }

    async function getToken() {
      const refreshed = await fetch("/api/auth/refresh", { method: "POST", credentials: "include" })
        .then((response) => response.json())
        .catch(() => undefined)
      const refreshToken = tokenFromValue(refreshed)
      if (refreshToken) return refreshToken
      for (const storage of [localStorage, sessionStorage]) {
        for (const key of Object.keys(storage)) {
          const token = tokenFromValue(storage.getItem(key))
          if (token) return token
        }
      }
      return undefined
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
  })

  const output = join(tmpdir(), "heelcode-promptlab-capture.json")
  await Bun.write(output, JSON.stringify(redactJSON(capture), null, 2))
  console.log(output)
} finally {
  await context.close()
}

async function fillFirst(page: Page, selectors: string[], value: string) {
  const deadline = Date.now() + 90_000
  while (Date.now() < deadline) {
    for (const selector of selectors) {
      const locator = page.locator(selector).first()
      if ((await locator.count()) === 0) continue
      if (!(await locator.isVisible().catch(() => false))) continue
      await locator.fill(value)
      return
    }
    await page.waitForTimeout(500)
  }
  await debugPage(page, "field-missing")
  throw new Error(`Unable to find field: ${selectors.join(", ")}`)
}

async function clickText(page: Page, labels: string[], options: { optional?: boolean } = {}) {
  const deadline = Date.now() + (options.optional ? 5_000 : 90_000)
  while (Date.now() < deadline) {
    for (const label of labels) {
      const locator = page.getByText(label, { exact: false }).first()
      if ((await locator.count()) === 0) continue
      if (!(await locator.isVisible().catch(() => false))) continue
      await locator.scrollIntoViewIfNeeded().catch(() => {})
      await locator.click({ timeout: 5_000 }).catch(() => locator.click({ force: true, timeout: 5_000 }))
      return true
    }
    await page.waitForTimeout(500)
  }
  if (options.optional) return false
  await debugPage(page, "button-missing")
  throw new Error(`Unable to find button/link: ${labels.join(", ")}`)
}

async function choosePasswordSignIn(page: Page) {
  if (await clickText(page, ["Use your password"], { optional: true })) return
  if (await clickSelector(page, "#moreOptions", { optional: true })) {
    if (await clickText(page, ["Use your password", "Password"], { optional: true })) return
  }
  if (await clickText(page, ["Other ways to sign in", "Sign in another way", "I can't use my"], { optional: true })) {
    await clickText(page, ["Password", "Use your password"], { optional: true })
  }
}

async function clickSelector(page: Page, selector: string, options: { optional?: boolean } = {}) {
  const deadline = Date.now() + (options.optional ? 5_000 : 90_000)
  while (Date.now() < deadline) {
    const locator = page.locator(selector).first()
    if ((await locator.count()) === 0) {
      await page.waitForTimeout(500)
      continue
    }
    if (!(await locator.isVisible().catch(() => false))) {
      await page.waitForTimeout(500)
      continue
    }
    await locator.click({ timeout: 5_000 }).catch(() => locator.click({ force: true, timeout: 5_000 }))
    return true
  }
  if (options.optional) return false
  await debugPage(page, "selector-missing")
  throw new Error(`Unable to find selector: ${selector}`)
}

async function debugPage(page: Page, label: string) {
  const prefix = join(tmpdir(), `heelcode-promptlab-${label}`)
  const summary = await page.evaluate(() => ({
    href: String((globalThis as { location?: { href?: string } }).location?.href ?? ""),
    title: String((globalThis as { document?: { title?: string } }).document?.title ?? ""),
    inputs: Array.from(((globalThis as any).document?.querySelectorAll("input") ?? []) as any[]).map((input) => ({
        type: input.getAttribute("type"),
        name: input.getAttribute("name"),
        id: input.getAttribute("id"),
        autocomplete: input.getAttribute("autocomplete"),
        placeholder: input.getAttribute("placeholder"),
      })),
    buttons: Array.from(
      ((globalThis as any).document?.querySelectorAll("button,a,input[type=submit]") ?? []) as any[],
    )
      .slice(0, 40)
      .map((element) => ({
        tag: element.tagName,
        text: element.textContent?.trim().slice(0, 80),
        type: element.getAttribute("type"),
        name: element.getAttribute("name"),
        id: element.getAttribute("id"),
      })),
  }))
  await Bun.write(`${prefix}.json`, JSON.stringify(redactJSON(summary), null, 2))
  await page.screenshot({ path: `${prefix}.png`, fullPage: true }).catch(() => {})
  console.error(`${prefix}.json`)
}

async function readPassword() {
  if (process.env.PROMPTLAB_ONYEN_PASSWORD) return process.env.PROMPTLAB_ONYEN_PASSWORD
  process.stderr.write("PromptLab ONYEN password: ")
  const restoreEcho = Boolean(process.stdin.isTTY)
  if (restoreEcho) Bun.spawnSync(["stty", "-echo"], { stdin: "inherit", stdout: "ignore", stderr: "ignore" })
  const chunks: Buffer[] = []
  try {
    for await (const chunk of process.stdin) {
      chunks.push(Buffer.from(chunk))
      if (Buffer.concat(chunks).includes(10)) break
    }
  } finally {
    if (restoreEcho) Bun.spawnSync(["stty", "echo"], { stdin: "inherit", stdout: "ignore", stderr: "ignore" })
  }
  process.stderr.write("\n")
  return Buffer.concat(chunks).toString("utf8").trimEnd()
}

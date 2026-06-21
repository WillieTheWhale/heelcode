import { mkdir, stat } from "node:fs/promises"
import { homedir } from "node:os"
import { join } from "node:path"

const SERVICE = "heelcode-promptlabd"
const HELPER_VERSION = "v1"
const HELPER_SOURCE = join(import.meta.dir, "keychain-helper.swift")

export type KeychainSecret = "onyen-username" | "onyen-password" | "promptlab-bearer-token" | "promptlab-cookie"

const ACCOUNT: Record<KeychainSecret, string> = {
  "onyen-username": "onyen.username",
  "onyen-password": "onyen.password",
  "promptlab-bearer-token": "promptlab.bearer-token",
  "promptlab-cookie": "promptlab.cookie",
}

const EXPECT_SET_SECRET = `
log_user 0
set timeout 30
set service $env(HEELCODE_KEYCHAIN_SERVICE)
set account $env(HEELCODE_KEYCHAIN_ACCOUNT)
set password [read stdin]
if {[string length $password] > 0 && [string index $password end] == "\\n"} {
  set password [string range $password 0 end-1]
}
spawn /usr/bin/security add-generic-password -s $service -a $account -T /usr/bin/security -U -w
expect {
  -re "(?i)password.*:" {
    send -- "$password\\r"
    exp_continue
  }
  eof {
    set result [wait]
    exit [lindex $result 3]
  }
  timeout {
    exit 124
  }
}
`


export class KeychainUnavailableError extends Error {
  constructor() {
    super("macOS Keychain is required for persistent PromptLab credentials")
    this.name = "KeychainUnavailableError"
  }
}

export async function getSecret(name: KeychainSecret): Promise<string | undefined> {
  ensureKeychain()
  const result = isPromptLabSessionSecret(name)
    ? await runHelper("get", name, false)
    : await runSecurity(["find-generic-password", "-s", SERVICE, "-a", ACCOUNT[name], "-w"], false)
  if (result.exitCode !== 0) return undefined
  const value = result.stdout.trimEnd()
  return value || undefined
}

export async function setSecret(name: KeychainSecret, value: string): Promise<void> {
  ensureKeychain()
  if (isPromptLabSessionSecret(name)) {
    await runSecurity(["delete-generic-password", "-s", SERVICE, "-a", ACCOUNT[name]], false)
    const result = await runHelper("set", name, true, value)
    if (result.exitCode !== 0) throw new Error(result.stderr.trim() || "Keychain set failed")
  } else {
    await runExpectSetSecret(name, value)
  }
}

export async function deleteSecret(name: KeychainSecret): Promise<void> {
  ensureKeychain()
  await runSecurity(["delete-generic-password", "-s", SERVICE, "-a", ACCOUNT[name]], false)
}

export async function hasSecret(name: KeychainSecret): Promise<boolean> {
  ensureKeychain()
  const result = await runSecurity(["find-generic-password", "-s", SERVICE, "-a", ACCOUNT[name]], false)
  return result.exitCode === 0
}

function ensureKeychain() {
  if (process.platform !== "darwin") throw new KeychainUnavailableError()
}

function isPromptLabSessionSecret(name: KeychainSecret) {
  return name === "promptlab-bearer-token" || name === "promptlab-cookie"
}

async function runExpectSetSecret(name: KeychainSecret, value: string) {
  const result = await runCommand(["expect", "-c", EXPECT_SET_SECRET], true, value, {
    HEELCODE_KEYCHAIN_SERVICE: SERVICE,
    HEELCODE_KEYCHAIN_ACCOUNT: ACCOUNT[name],
  })
  if (result.exitCode !== 0) throw new Error(result.stderr.trim() || "Keychain set failed")
}

async function runSecurity(args: string[], required: boolean) {
  return runCommand(["security", ...args], required)
}

let helperPathPromise: Promise<string> | undefined

async function runHelper(
  op: "get" | "set" | "exists" | "delete",
  name: KeychainSecret,
  required: boolean,
  stdin?: string,
) {
  const helper = await keychainHelperPath()
  return runCommand([helper, op, SERVICE, ACCOUNT[name]], required, stdin)
}

async function keychainHelperPath() {
  helperPathPromise ??= compileKeychainHelper()
  return helperPathPromise
}

async function compileKeychainHelper() {
  const dir = join(homedir(), "Library", "Caches", "heelcode")
  const output = join(dir, `promptlab-keychain-helper-${HELPER_VERSION}`)
  await mkdir(dir, { recursive: true })

  const [sourceStat, outputStat] = await Promise.all([stat(HELPER_SOURCE), stat(output).catch(() => undefined)])
  if (outputStat && outputStat.mtimeMs >= sourceStat.mtimeMs) return output

  const result = await runCommand(["swiftc", HELPER_SOURCE, "-o", output], true)
  if (result.exitCode !== 0) throw new Error(result.stderr.trim() || "swiftc failed")
  return output
}

async function runCommand(command: string[], required: boolean, stdin?: string, env?: Record<string, string>) {
  const proc = Bun.spawn(command, {
    env: env ? { ...process.env, ...env } : undefined,
    stdin: stdin === undefined ? undefined : "pipe",
    stdout: "pipe",
    stderr: "pipe",
  })
  if (stdin !== undefined) {
    proc.stdin.write(stdin)
    proc.stdin.end()
  }
  const [stdout, stderr, exitCode] = await Promise.all([
    new Response(proc.stdout).text(),
    new Response(proc.stderr).text(),
    proc.exited,
  ])
  if (required && exitCode !== 0) throw new Error(stderr.trim() || `${command[0]} failed`)
  return { stdout, stderr, exitCode }
}

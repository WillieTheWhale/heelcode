import { $ } from "bun"
import { chmod, copyFile, mkdir } from "node:fs/promises"
import { join, resolve } from "node:path"

export type Channel = "dev" | "beta" | "prod"

export function resolveChannel(): Channel {
  const raw = Bun.env.OPENCODE_CHANNEL
  if (raw === "dev" || raw === "beta" || raw === "prod") return raw
  return "dev"
}

export const CLI_BINARIES: Array<{ rustTarget: string; directory: string; os: string; cpu: string }> = [
  {
    rustTarget: "aarch64-apple-darwin",
    directory: "cli-darwin-arm64",
    os: "darwin",
    cpu: "arm64",
  },
  {
    rustTarget: "x86_64-apple-darwin",
    directory: "cli-darwin-x64-baseline",
    os: "darwin",
    cpu: "x64",
  },
  {
    rustTarget: "aarch64-pc-windows-msvc",
    directory: "cli-windows-arm64",
    os: "win32",
    cpu: "arm64",
  },
  {
    rustTarget: "x86_64-pc-windows-msvc",
    directory: "cli-windows-x64-baseline",
    os: "win32",
    cpu: "x64",
  },
  {
    rustTarget: "x86_64-unknown-linux-gnu",
    directory: "cli-linux-x64-baseline",
    os: "linux",
    cpu: "x64",
  },
  {
    rustTarget: "aarch64-unknown-linux-gnu",
    directory: "cli-linux-arm64",
    os: "linux",
    cpu: "arm64",
  },
]

export const RUST_TARGET = Bun.env.RUST_TARGET

function nativeTarget() {
  const { platform, arch } = process
  if (platform === "darwin") return arch === "arm64" ? "aarch64-apple-darwin" : "x86_64-apple-darwin"
  if (platform === "win32") return arch === "arm64" ? "aarch64-pc-windows-msvc" : "x86_64-pc-windows-msvc"
  if (platform === "linux") return arch === "arm64" ? "aarch64-unknown-linux-gnu" : "x86_64-unknown-linux-gnu"
  throw new Error(`Unsupported platform: ${platform}/${arch}`)
}

export function getCurrentCli(target = RUST_TARGET ?? nativeTarget()) {
  const binaryConfig = CLI_BINARIES.find((item) => item.rustTarget === target)
  if (!binaryConfig) throw new Error(`CLI configuration not available for target '${target}'`)

  return binaryConfig
}

export async function buildCliToResources() {
  const cli = getCurrentCli()
  const directory = resolve(import.meta.dirname, "../../cli")
  const dest = resolve(import.meta.dirname, "../resources", cli.os === "win32" ? "opencode-cli.exe" : "opencode-cli")
  const flags = cli.os === process.platform && cli.cpu === process.arch ? ["--single", "--baseline"] : []
  // Bundle the fork's v2 CLI and workspace packages instead of a published OpenCode CLI.
  await $`bun run script/build.ts --skip-install ${flags}`.cwd(directory)
  await mkdir(resolve(import.meta.dirname, "../resources"), { recursive: true })
  await copyFile(join(directory, "dist", cli.directory, "bin", cli.os === "win32" ? "lildax.exe" : "lildax"), dest)
  if (process.platform !== "win32") await chmod(dest, 0o755)
  if (process.platform === "win32" && process.env.GITHUB_ACTIONS === "true") {
    await $`pwsh -NoLogo -NoProfile -ExecutionPolicy Bypass -File ../../script/sign-windows.ps1 ${dest}`
  }
  if (process.platform === "darwin") await $`codesign --force --sign - ${dest}`

  console.log(`Built local ${cli.directory} and copied it to ${dest}`)
}

export function windowsify(path: string) {
  if (path.endsWith(".exe")) return path
  return `${path}${process.platform === "win32" ? ".exe" : ""}`
}

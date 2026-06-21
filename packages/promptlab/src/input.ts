export async function readSecret(prompt: string, envValue?: string): Promise<string> {
  if (envValue) return envValue
  process.stderr.write(prompt)
  const restoreEcho = process.stdin.isTTY
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

export function argValue(args: string[], name: string): string | undefined {
  const index = args.indexOf(name)
  if (index === -1) return undefined
  return args[index + 1]
}

import { EOL } from "os"
import { Schema } from "effect"
import { logo as glyphs } from "./logo"

const wordmark = glyphs.left.map((row, index) => `${row} ${glyphs.right[index] ?? ""}`.trimEnd())

type RGB = [number, number, number]

type LogoPalette = {
  fgStart: RGB
  fgEnd: RGB
  shadow: RGB
  fill: RGB
  dim?: boolean
}

const UNCCyan: LogoPalette = {
  fgStart: [21, 49, 96],
  fgEnd: [86, 168, 225],
  shadow: [12, 26, 58],
  fill: [17, 29, 56],
}

const UNCBlue: LogoPalette = {
  fgStart: [75, 156, 211],
  fgEnd: [168, 218, 250],
  shadow: [31, 76, 132],
  fill: [22, 44, 86],
  dim: true,
}

function mix(a: RGB, b: RGB, t: number): RGB {
  return [
    Math.round(a[0] + (b[0] - a[0]) * t),
    Math.round(a[1] + (b[1] - a[1]) * t),
    Math.round(a[2] + (b[2] - a[2]) * t),
  ]
}

function blendToDim(base: RGB, dimAmount: number, dimTint: RGB): RGB {
  return mix(base, dimTint, dimAmount)
}

function fg(rgb: RGB): string {
  return `\x1b[38;2;${rgb[0]};${rgb[1]};${rgb[2]}m`
}

function bg(rgb: RGB): string {
  return `\x1b[48;2;${rgb[0]};${rgb[1]};${rgb[2]}m`
}

export class CancelledError extends Schema.TaggedErrorClass<CancelledError>()("UICancelledError", {}) {}

export const Style = {
  TEXT_HIGHLIGHT: "\x1b[96m",
  TEXT_HIGHLIGHT_BOLD: "\x1b[96m\x1b[1m",
  TEXT_DIM: "\x1b[90m",
  TEXT_DIM_BOLD: "\x1b[90m\x1b[1m",
  TEXT_NORMAL: "\x1b[0m",
  TEXT_NORMAL_BOLD: "\x1b[1m",
  TEXT_WARNING: "\x1b[93m",
  TEXT_WARNING_BOLD: "\x1b[93m\x1b[1m",
  TEXT_DANGER: "\x1b[91m",
  TEXT_DANGER_BOLD: "\x1b[91m\x1b[1m",
  TEXT_SUCCESS: "\x1b[92m",
  TEXT_SUCCESS_BOLD: "\x1b[92m\x1b[1m",
  TEXT_INFO: "\x1b[94m",
  TEXT_INFO_BOLD: "\x1b[94m\x1b[1m",
}

export function println(...message: string[]) {
  print(...message)
  process.stderr.write(EOL)
}

export function print(...message: string[]) {
  blank = false
  process.stderr.write(message.join(" "))
}

let blank = false
export function empty() {
  if (blank) return
  println("" + Style.TEXT_NORMAL)
  blank = true
}

export function logo(pad?: string) {
  if (!process.stdout.isTTY && !process.stderr.isTTY) {
    const result = []
    for (const row of wordmark) {
      if (pad) result.push(pad)
      result.push(row)
      result.push(EOL)
    }
    return result.join("").trimEnd()
  }

  const result: string[] = []
  const reset = "\x1b[0m"
  const rowCount = Math.max(glyphs.left.length, glyphs.right.length)
  const left = {
    fgStart: UNCCyan.fgStart,
    fgEnd: UNCCyan.fgEnd,
    shadow: UNCCyan.shadow,
    fill: UNCCyan.fill,
  }
  const right = {
    fgStart: UNCBlue.fgStart,
    fgEnd: UNCBlue.fgEnd,
    shadow: UNCBlue.shadow,
    fill: UNCBlue.fill,
    dim: true,
  }
  const gap = " "
  const translucent: RGB = [196, 205, 219]
  const draw = (line: string, palette: LogoPalette, rowIndex: number) => {
    const parts: string[] = []
    const width = Math.max(1, line.length - 1)
    for (const [charIndex, char] of [...line].entries()) {
      if (char === "_") {
        parts.push(bg(palette.fill), " ", reset)
        continue
      }
      if (char === "^") {
        parts.push(fg(palette.shadow), bg(palette.fill), "▀", reset)
        continue
      }
      if (char === "~") {
        parts.push(fg(palette.shadow), "▀", reset)
        continue
      }
      if (char === " ") {
        parts.push(" ")
        continue
      }

      const rowWeight = rowCount <= 1 ? 0 : (rowIndex + 1) / rowCount
      const colWeight = line.length <= 1 ? 0 : charIndex / width
      const depth = Math.min(1, rowWeight * 0.48 + colWeight * 0.52)
      let color = mix(palette.fgStart, palette.fgEnd, depth)
      if (palette.dim) {
        color = blendToDim(color, 0.56, translucent)
        parts.push("\x1b[2m")
      }
      parts.push(fg(color), char, reset)
    }
    return parts.join("")
  }
  glyphs.left.forEach((row, index) => {
    if (pad) result.push(pad)
    result.push(draw(row, left, index))
    result.push(gap)
    const other = glyphs.right[index] ?? ""
    result.push(draw(other, right, index))
    result.push(EOL)
  })
  return result.join("").trimEnd()
}

export async function input(prompt: string): Promise<string> {
  const readline = require("readline")
  const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout,
  })

  return new Promise((resolve) => {
    rl.question(prompt, (answer: string) => {
      rl.close()
      resolve(answer.trim())
    })
  })
}

export function error(message: string) {
  if (message.startsWith("Error: ")) {
    message = message.slice("Error: ".length)
  }
  println(Style.TEXT_DANGER_BOLD + "Error: " + Style.TEXT_NORMAL + message)
}

export function markdown(text: string): string {
  return text
}

export * as UI from "./ui"

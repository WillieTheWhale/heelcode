import { expect, test } from "bun:test"
import { allThemes, resolveTheme } from "."

test("heelcode theme uses UNC blue identity colors", () => {
  const theme = allThemes().heelcode
  expect(theme).toBeDefined()
  expect(theme.defs).toMatchObject({
    darkStep9: "#4B9CD3",
    lightStep9: "#4B9CD3",
    lightStep10: "#13294B",
    lightAccent: "#13294B",
  })

  const dark = resolveTheme(theme, "dark")
  const light = resolveTheme(theme, "light")

  expect(dark.primary.toInts()).toEqual([75, 156, 211, 255])
  expect(light.primary.toInts()).toEqual([75, 156, 211, 255])
  expect(light.accent.toInts()).toEqual([19, 41, 75, 255])
})

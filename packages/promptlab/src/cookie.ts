export function updatedCookieHeader(current: string | undefined, headers: Headers) {
  const cookies = new Map(
    (current ?? "")
      .split(";")
      .map((value) => value.trim())
      .filter(Boolean)
      .flatMap((value): Array<[string, string]> => {
        const split = value.indexOf("=")
        if (split <= 0) return []
        return [[value.slice(0, split), value.slice(split + 1)]]
      }),
  )
  const values = (headers as Headers & { getSetCookie?: () => string[] }).getSetCookie?.() ?? splitSetCookie(headers)
  for (const value of values) {
    const [pair, ...attributes] = value.split(";").map((part) => part.trim())
    const split = pair?.indexOf("=") ?? -1
    if (split <= 0) continue
    const name = pair!.slice(0, split)
    const expired = attributes.some((attribute) => {
      const [key, raw] = attribute.split("=", 2)
      if (key?.toLowerCase() === "max-age") return Number(raw) <= 0
      if (key?.toLowerCase() !== "expires" || !raw) return false
      const expires = Date.parse(raw)
      return Number.isFinite(expires) && expires <= Date.now()
    })
    if (expired) cookies.delete(name)
    else cookies.set(name, pair!.slice(split + 1))
  }
  if (!cookies.size) return undefined
  return Array.from(cookies, ([name, value]) => `${name}=${value}`).join("; ")
}

function splitSetCookie(headers: Headers) {
  const value = headers.get("set-cookie")
  if (!value) return []
  return value.split(/,(?=\s*[^;,\s]+=)/)
}

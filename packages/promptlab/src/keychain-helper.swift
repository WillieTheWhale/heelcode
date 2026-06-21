import Foundation
import Security

guard CommandLine.arguments.count == 4 else {
  fputs("Usage: keychain-helper <get|set|exists|delete> <service> <account>\n", stderr)
  exit(64)
}

let op = CommandLine.arguments[1]
let service = CommandLine.arguments[2]
let account = CommandLine.arguments[3]

func baseQuery() -> [String: Any] {
  [
    kSecClass as String: kSecClassGenericPassword,
    kSecAttrService as String: service,
    kSecAttrAccount as String: account,
  ]
}

func fail(_ message: String, _ status: OSStatus) -> Never {
  fputs("\(message): \(status)\n", stderr)
  exit(1)
}

switch op {
case "get":
  var query = baseQuery()
  query[kSecReturnData as String] = true
  query[kSecMatchLimit as String] = kSecMatchLimitOne
  var result: CFTypeRef?
  let status = SecItemCopyMatching(query as CFDictionary, &result)
  if status == errSecItemNotFound { exit(44) }
  if status != errSecSuccess { fail("SecItemCopyMatching failed", status) }
  guard let data = result as? Data else { exit(1) }
  FileHandle.standardOutput.write(data)

case "set":
  let data = FileHandle.standardInput.readDataToEndOfFile()
  let updateStatus = SecItemUpdate(baseQuery() as CFDictionary, [kSecValueData as String: data] as CFDictionary)
  if updateStatus == errSecSuccess { exit(0) }
  if updateStatus != errSecItemNotFound { fail("SecItemUpdate failed", updateStatus) }
  var query = baseQuery()
  query[kSecValueData as String] = data
  let addStatus = SecItemAdd(query as CFDictionary, nil)
  if addStatus != errSecSuccess { fail("SecItemAdd failed", addStatus) }

case "exists":
  var query = baseQuery()
  query[kSecReturnAttributes as String] = true
  query[kSecMatchLimit as String] = kSecMatchLimitOne
  var result: CFTypeRef?
  let status = SecItemCopyMatching(query as CFDictionary, &result)
  if status == errSecItemNotFound { exit(44) }
  if status != errSecSuccess { fail("SecItemCopyMatching failed", status) }

case "delete":
  let status = SecItemDelete(baseQuery() as CFDictionary)
  if status == errSecSuccess || status == errSecItemNotFound { exit(0) }
  fail("SecItemDelete failed", status)

default:
  fputs("Unknown Keychain operation: \(op)\n", stderr)
  exit(64)
}

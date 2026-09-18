# HeelCode policy engine

Java 17+ prototype for generating course policies, classifying requested assistance, and enforcing an explicit admission decision. Uses the existing Codex CLI sign-in for Luna/Terra research runs. This adapter is not a claim of university API access or a production deployment architecture.

## Build and run

```sh
cd packages/policy-engine
mvn package
./run --help
./run generate sys301 fixtures/systems/course /tmp/my-new-policy-run gpt-5.6-luna
./run activate /tmp/my-course-workspace fixtures/systems/course /tmp/my-new-policy-run
```

The repository launcher exposes the same commands as `heelcode policy`. On Windows use `java -jar target/policy-engine-0.1.0-SNAPSHOT.jar ...`; the Java code and JSON protocol are identical. Windows has not yet been smoke-tested. The POSIX launcher chooses Homebrew Java on macOS because the system default may still be Java 8. Override with `HEELCODE_JAVA=/path/to/java`.

## Piping and persistence

Single request, EOF-delimited:

```sh
printf '%s\n' '{"requestId":"r1","sessionId":"","assignment":"a1","prompt":"Explain fork versus exec."}' |
  heelcode policy check /tmp/my-course-workspace gpt-5.6-luna
```

For a long-lived frontend connection start:

```sh
heelcode policy stdio /tmp/my-course-workspace gpt-5.6-luna
```

Write one JSON object followed by a newline per request. A response arrives without closing stdin. Read one JSON response line; save its `sessionId` and send it with the next request. Request fields are all required; `sessionId: ""` creates a session. `requestId` must be unique within that session. An exact retry with the same request ID returns the original result without another model call. Reusing the ID with different content fails. Requests are processed sequentially; frontend clients should await each response. Error objects contain `forward:false`; successful objects contain `decision.forward`. Treat every error, missing response, `deny`, or `clarify` as **do not forward**.

Policy sessions use `hps_...` IDs and are stored under the workspace's `.heelcode-policy/sessions`. They persist across restarts and are pinned to an assignment, workspace, source digest, and policy digest. The last six turns are supplied to the extractor; the full local admission history is saved (maximum 200 turns). This is a bounded history policy, not an unlimited model context window.

**These are admission sessions, not OpenCode inference sessions.** Existing inference uses `heelcode run --format json --session ses_...`. A frontend must maintain both IDs if it calls the gate and OpenCode separately. `heelcode run` itself still takes one EOF-delimited input per invocation; it does not gain persistent JSONL stdin from this package. The policy gate does not automatically intercept the TUI or raw `heelcode run`, and an allowed response is not a generated solution. It is the caller's responsibility to forward only allowed inputs. A direct enforcement bridge remains a separate integration step.

## Components

- `Course`: bounded UTF-8 Markdown/text ingestion with stable hashes. Unsupported files and symlinks are refused, not silently skipped. PDF/DOCX extraction and LMS crawling are not implemented.
- `CodexModel`/`Prompts`: tool-free structured generation with explicit Luna/Terra selection, timeouts, raw model evidence and usage. No Sol/Astra fallback. Course documents are evidence, not executable instructions.
- `Policy`: exhaustive assistance rules for each assignment, including the course baseline, with validated verbatim citations. Missing/overlapping categories and invented quotes fail validation. Quote existence does not prove the rule interprets the quote correctly.
- `Classifier`: model extraction of requested activities, concrete attempt evidence, ambiguity, and explicit deception; deterministic mapping to allow/deny/clarify. It does not infer whether a student understands the material.
- `Workspace`: explicit activation, stale-source refusal, scoped durable sessions, atomic writes, exclusive per-session lock, exact-request replay.
- `Evaluation`: blinded feature extraction (no gold labels sent), frozen cases, confusion matrix, unsafe allowances and false denials. Batched offline extraction is not a latency estimate for one live student request.

## Tests and research evidence

`mvn test` is offline. `script/live-sessions.ts` runs real Luna inference through HeelCode and tests saved context across process restarts and isolation of a separate session. `script/live-policy-sessions.ts` tests the JSONL gate, follow-up context, amendment enforcement, and replay after restart. Both consume model allowance and save raw evidence; invoke explicitly with Bun from this package. Do not run from the monorepo root.

The fictional sources are in `fixtures/systems/course`; gold cases are separately in `fixtures/systems/evaluation.json`. Model run directories preserve prompts, schema, outputs, events, metadata and errors. A new run must use a new directory. Research runs contain synthetic data; production prompt logging needs consent, retention, access controls and IRB review where applicable.

## Boundaries

This is an English-language research prototype, not a secure academic-integrity enforcement product. A user who can edit their local workspace can replace a policy. SHA-256 detects accidental drift; it is not a signature or authorization mechanism. The source folder and activation command must be controlled by a trusted instructor/operator in deployment. Generation is explicit after ingestion; source changes fail closed until regenerated and reactivated. There is no upload portal, automatic file watcher, fine-tuned encoder, output filter, tool-action guard, or proof of learning. A syntactically valid policy is a draft until an instructor has reviewed its meaning.

Model-provider failures do not admit prompts. The CLI is a research adapter with substantial Codex context overhead; measured token counts should not be presented as the minimum cost of a future direct-API classifier. No dollar cost is inferred from ChatGPT subscription usage.

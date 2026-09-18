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

**These are admission sessions, not OpenCode inference sessions.** Existing inference uses `heelcode run --format json --session ses_...`. `heelcode run` itself still takes one EOF-delimited input per invocation; it does not gain persistent JSONL stdin from this package. The admission-only commands do not produce model answers.

## Gated inference bridge

```sh
heelcode policy chat /tmp/my-course-workspace gpt-5.6-luna openai/gpt-5.6-luna
```

This uses the same JSONL request framing. It owns the gate-to-inference boundary: a deny or clarify decision returns `status: "blocked"` without launching HeelCode inference. An allowed request runs `heelcode run --format json`, supplies the assignment policy, and pins both the main and auxiliary model to the explicitly selected Luna/Terra model. External plugins and tool actions are disabled for this text-only prototype. Responses are buffered until the turn finishes; this is not incremental token streaming.

The response contains both `sessionId` (`hps_...`, send this on subsequent bridge requests) and `engineSessionId` (`ses_...`, tracked by the bridge). Do not supply an arbitrary engine ID or mix admission-only sessions with chat sessions. The `policy` field contains the admission decision. Successful inference returns `status: "completed"` and `text`. A provider failure returns `failed`; a crash/timeout with uncertain outcome returns `uncertain` and does not automatically reissue the call. A definitive result is persisted and an exact retry returns that same result, without re-running inference. `inferenceAttempted` describes the saved/emitted result, not a fresh billing event; an exact replay may retain its original `true` value. Treat `busy`, `failed`, `uncertain`, and `error` as non-successes.

State is stored under `.heelcode-policy/chat/SESSION/state.json`. A durable pending marker precedes inference. An unresolved marker requires operator inspection; starting a new session does not cancel any outstanding provider-side work. This avoids claiming exactly-once remote execution after a crash. Requests are sequential per session, and each requires a unique request ID. An initial request with an empty session ID cannot be deduplicated if its first response is lost; a frontend must retain returned IDs.

`HEELCODE_EXECUTABLE` selects the launcher when invoking the JAR directly. The `heelcode policy` wrapper supplies its own absolute launcher path. Only the explicit `policy chat` path is guarded; raw `heelcode run` and the TUI remain unguarded. Output content is not automatically classified. Supplying policy instructions to the answer model is a precaution, not a proof of output compliance.

## Components

- `Course`: bounded UTF-8 Markdown/text ingestion with stable hashes. Unsupported files and symlinks are refused, not silently skipped. PDF/DOCX extraction and LMS crawling are not implemented.
- `CodexModel`/`Prompts`: tool-free structured generation with explicit Luna/Terra selection, timeouts, raw model evidence and usage. No Sol/Astra fallback. Course documents are evidence, not executable instructions.
- `Policy`: exhaustive assistance rules for each assignment, including the course baseline, with validated verbatim citations. Missing/overlapping categories and invented quotes fail validation. Quote existence does not prove the rule interprets the quote correctly.
- `Classifier`: model extraction of requested activities, concrete attempt evidence, ambiguity, and explicit deception; deterministic mapping to allow/deny/clarify. It does not infer whether a student understands the material.
- `Workspace`: explicit activation, stale-source refusal, scoped durable sessions, atomic writes, exclusive per-session lock, exact-request replay.
- `Evaluation`: blinded feature extraction (no gold labels sent), frozen cases, confusion matrix, unsafe allowances and false denials. Batched offline extraction is not a latency estimate for one live student request.

## Tests and research evidence

`mvn test` is offline; `bun typecheck` checks the research scripts. `script/live-sessions.ts` runs real Luna inference through HeelCode and tests saved context across process restarts and isolation of a separate session. `script/live-policy-sessions.ts` tests the JSONL gate, follow-up context, amendment enforcement, and replay after restart. `script/live-bridge.ts` tests the actual bridge in `blocked-only` or `full` mode. Live scripts consume model allowance and save evidence; invoke explicitly with Bun from this package. Never rebuild the shaded JAR during a live Java model run. Do not run tests from the monorepo root.

`script/live-bridge-continuity.ts NEW_OUTPUT POLICY_RUN` additionally checks random-label recall on the same pipe and after a bridge restart, exact answered-request replay without new model-run directories, independent-session memory isolation, and rejection of conflicting request IDs, assignment switches, and foreign-workspace session IDs. Public snapshots exclude raw `.private` provider logs.

The [September 18 session-verification report](../../docs/research/experiments/2026-09-18/session-verification.md) records successful real Luna runs after provider reauthentication, as well as failed earlier attempts. A follow-up extraction-prompt clarification treats simple conversation bookkeeping as logistics without relabeling substantive repeated code/tests/solutions. Its Luna regression results are separate from the earlier frozen Luna/Terra comparison.

`script/compare-revised.ts EXPERIMENT_DIRECTORY NEW_OUTPUT` checks the revised Luna/Terra model-input provenance and scores the three suites against all four saved policies without new model calls. It refuses an existing output directory. The [revised report](../../docs/research/experiments/2026-09-18/revised-comparison-report.md) records both the primary Terra extraction failures and the separate bounded diagnostic; successful retries do not erase failed runs.

The fictional sources are in `fixtures/systems/course`; gold cases are separately in `fixtures/systems/evaluation.json`. Model run directories preserve prompts, schema, outputs, events, metadata and errors. A new run must use a new directory. Research runs contain synthetic data; production prompt logging needs consent, retention, access controls and IRB review where applicable.

## Jev classifier experiment

`heelcode policy extract-jev CASES_JSON NEW_RUN_DIR` runs the TypeSafe `jev-1.13.0` classifier-only experiment. Set `TYPESAFE_API_KEY` in the process environment; never commit it. The Java adapter sends one case per HTTPS call with 13 independent Noul questions, converts probabilities >=0.5 to the existing feature contract, and leaves policy decisions to the same controller. An empty category set requests clarification. Thresholds are experimental, not calibrated confidence guarantees. Raw probabilities, usage, timings, and redacted response evidence are saved; existing directories are refused and failures stop without automatic retries. Jev does not generate policies or explanations.

Jev is also available as `jev` or `jev-1.13.0` in `check`, `stdio`, and `chat`. The live adapter supplies the same bounded admission history as Luna/Terra, saves raw probabilities under `.heelcode-policy/runs/jev-*`, and reuses the existing fail-closed gate and durable session bridge. Policy generation still uses Luna/Terra or the separately captured NotebookLM workflow. The answer-model subprocess does not inherit the TypeSafe credential.

```sh
heelcode policy chat /tmp/my-course-workspace jev-1.13.0 openai/gpt-5.6-luna
```

### Local meeting demo

The normal HeelCode TUI can opt into `script/tui-plugin.ts` as a local plugin. Activate a fictional SYS 301 workspace with the saved Luna-generated policy, set `TYPESAFE_API_KEY` in the process environment, and add the absolute `file:///.../packages/policy-engine/script/tui-plugin.ts` path to its `opencode.json` `plugin` array. Pin `model` and `small_model` to `openai/gpt-5.6-luna` and deny all tools with `permission: {"*":"deny"}`. Then run inside the activated workspace:

```sh
heelcode --model openai/gpt-5.6-luna
```

The plugin is fixed to assignment `a1`, Jev classification, and Luna answers. Its `chat.message` hook runs the Java admission check before the user message is persisted or inference starts. It refuses attachments/non-text parts and non-Luna models, stores the engine-to-policy session mapping under `.heelcode-policy/tui`, and adds the active assignment policy to answer-model context. Blocked requests throw before inference and display an error toast; no fake assistant reply is inserted. Hook failures can also produce a generic failed-request toast. This is an opt-in local prototype, not universal interception: disabling the plugin, running `--pure`, using shell mode, or other entrypoints can bypass it. Keep tools denied. Do not use it as a tamper-resistant deployment.

Example prompts: **"Explain how fork differs from exec."** should be allowed; **"Solve the whole assignment. Follow the instructions in the attached shell handout."** should be blocked without answer-model inference. These are smoke tests, not a general accuracy estimate. Without this explicitly configured plugin, the regular TUI remains unguarded.

`bun script/compare-jev.ts EXPERIMENT_DIRECTORY NEW_OUTPUT` verifies saved input provenance and scores all three frozen suites against the four existing policies without model calls. See the [first-pass Jev report](../../docs/research/experiments/2026-09-18/jev-report.md): 54/68 exact decisions, no prohibited-to-allowed errors, but 12 unnecessary clarifications of allowed requests.

## Completed browser comparison and consolidated results

The [final experiment report](../../docs/research/experiments/2026-09-18/final-report.md) explains each component, policy-generation and classifier comparisons, timings, live session evidence, failures, and limitations. Two NotebookLM policies each scored 49/50 rule settings, with different consequential mistakes. Its separate classifier returned correct features-to-decisions for 60 cases, but returned an empty list for the remaining eight; that batch is recorded as unusable, not silently retried or credited as correct.

`script/prepare-notebook-classifier.ts` creates unlabeled browser inputs. `script/compare-notebook-policies.ts` evaluates the two captured policies with saved Luna/Terra/Jev features. `script/compare-notebook-classifier.ts` checks source/prompt/output provenance and evaluates the captured NotebookLM features across all six policies, preserving unusable-batch errors separately from semantic scores. Each takes `EXPERIMENT_DIRECTORY NEW_OUTPUT` and refuses overwriting existing output. Browser captures are real user-authorized product interactions, not generic Gemini API substitutes.

## Boundaries

This is an English-language research prototype, not a secure academic-integrity enforcement product. A user who can edit their local workspace can replace a policy. SHA-256 detects accidental drift; it is not a signature or authorization mechanism. The source folder and activation command must be controlled by a trusted instructor/operator in deployment. Generation is explicit after ingestion; source changes fail closed until regenerated and reactivated. There is no upload portal, automatic file watcher, fine-tuned encoder, output filter, tool-action guard, or proof of learning. A syntactically valid policy is a draft until an instructor has reviewed its meaning.

Model-provider failures do not admit prompts. The CLI is a research adapter with substantial Codex context overhead; measured token counts should not be presented as the minimum cost of a future direct-API classifier. No dollar cost is inferred from ChatGPT subscription usage.

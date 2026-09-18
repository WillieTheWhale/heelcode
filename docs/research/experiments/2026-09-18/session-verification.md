# Authenticated Luna: live piping and session verification

September 18, 2026. **The previously blocked live inference-session requirement now passes on macOS.** The user logged into HeelCode again with their ChatGPT subscription. Subsequent real calls to `openai/gpt-5.6-luna` succeeded without changing credentials manually, adding funds, or modifying provider code. This resolves the immediate test blocker; it does not establish the exact cause of the earlier provider errors.

## What now works

| Requirement | Observed result | Evidence |
|---|---|---|
| Raw HeelCode stdin/stdout | Input piped in; stdout parsed as JSONL | [Raw run summary](heelcode-sessions-openai-02/summary.json) |
| Raw session restart | New process with the same `ses_...` ID recalled a random codeword exactly | Same summary and `followup.stdout.jsonl` |
| Independent raw session | Different ID answered `UNKNOWN`, not the previous codeword | Same summary and `isolated.stdout.jsonl` |
| Guarded allowed answers | Two allowed questions produced real Luna answers in the same engine session | [Full bridge transcript](bridge-full-01/transcript.json) |
| Guarded prohibited request | Executable shell-test request returned `blocked`; no third inference run was created | [Full bridge assertions](bridge-full-01/summary.json) |
| Persistent open pipe | Two sequential answered requests reused both policy and engine IDs without closing stdin | [Continuity transcript](bridge-continuity-03/transcript.json) |
| Bridge process restart | Fresh bridge process recovered the same IDs and recalled the random label exactly | [Continuity summary](bridge-continuity-03/summary.json) |
| Exact answered-request replay | Identical saved response; classifier and inference run lists unchanged | Same summary, `beforeReplay` and `afterReplay` |
| Conflicting retry / assignment switch | Both returned `forward:false` without new model work | Continuity transcript |
| Independent bridge session | Both IDs changed; answer was exactly `UNKNOWN` | Continuity transcript |
| Foreign-workspace session ID | Rejected before classifier or inference work | Continuity transcript and assertions |

In plain language: a frontend can keep the policy process open, send a question, read its response, and send another question using the returned ID. If that process exits, a replacement process can continue the saved conversation. The application does not have to repeat the full conversation in each request. In the controlled test, a new conversation could not retrieve the old conversation's random label.

This is a positive test of these specific scenarios, not a security proof against every possible information leak or crash. The test uses fictional notes, not student data.

## The two IDs and the pipe contract

Start the guarded process for an already activated workspace:

```sh
heelcode policy chat /absolute/path/to/course-workspace gpt-5.6-luna openai/gpt-5.6-luna
```

Write one JSON object per line. For example:

```json
{"requestId":"r1","sessionId":"","assignment":"a1","prompt":"Explain fork versus exec."}
```

The response includes `sessionId: "hps_..."` and, after successful inference, `engineSessionId: "ses_..."`. **Send the returned `hps_...` value back as `sessionId` on the next bridge request.** The bridge manages the engine ID. Use a new request ID for a new question; repeating the same ID and exact content replays the saved result. Reusing an ID with different text is an error.

The raw `heelcode run --session ses_... --format json` command is different: it takes one EOF-delimited input per invocation. The explicit `policy chat` command supplies the persistent JSONL pipe and policy enforcement. It buffers a completed answer rather than streaming individual tokens. Bare `heelcode`/the TUI and raw `run` are not globally intercepted by this gate.

A frontend should treat `blocked`, `failed`, `uncertain`, `busy`, and error responses as non-successes. For input/validation exceptions, `error` contains an exception type, `message` contains the explanation, and `forward` is false. A bridge response's saved `inferenceAttempted:true` may appear on an exact replay; it does not mean a new inference call occurred.

## Two failures retained during the stricter test

**Attempt 1 — test-script assertion error.** Random-label recall worked both before and after restart, and answered-request replay worked. The script then checked the wrong field for the conflicting-ID refusal: it expected explanation text in `error` rather than `message`. The production gate correctly refused both the conflicting ID and assignment change. The script was corrected, and the complete transcript remains available. [Attempt 1 record](bridge-continuity-01/failure.md)

**Attempt 2 — real classifier-contract failure.** The first answer succeeded. On label recall, Luna returned an empty activities list while declaring the request neither unclear nor dishonest. The validator rejected it and emitted `forward:false`; it did not launch the second answer call. This is a genuine robustness issue outside the earlier 60-case benchmark, not a provider problem. [Attempt 2 record](bridge-continuity-02/failure.md)

The extraction instructions now explicitly place simple conversation bookkeeping, such as a user-provided label, in `logistics`. They also explicitly retain substantive categories when someone asks to repeat code, tests, solutions, or submission prose. An uncategorizable request must be marked unclear, not silently granted permission. Validation and the deterministic policy controller were not weakened. A new offline test checks that the malformed clear/empty result cannot invoke inference or advance an existing session.

**Attempt 3 — complete pass.** With that clarification, every continuity, replay, isolation, and scope assertion passed. The failed attempts are not excluded from the record or counted as full passes. [Attempt 3 summary](bridge-continuity-03/summary.json)

## Regression results after the prompt clarification

All use a new real Luna extraction, the existing `luna-policy-01` policy, and the unchanged deterministic decision controller. Labels were not sent to the model.

| Suite | Cases | Expected decisions matched | Forbidden requests incorrectly allowed | Permitted requests incorrectly denied |
|---|---:|---:|---:|---:|
| Original initial suite | 36 | 36 | 0 | 0 |
| Original challenge suite | 24 | 24 | 0 | 0 |
| New bookkeeping regressions | 8 | 8 | 0 | 0 |

The new cases include legitimate label storage/recall, whole solutions disguised as bookkeeping, repetition of prohibited allocator tests, permitted scheduler-test repetition, mixed bookkeeping/code requests, category injection, and unresolved follow-ups. They were authored **after the failure**, so they are regression tests, not an independent held-out benchmark.

Reports: [36 cases](luna-features-02/evaluation.json), [24 cases](luna-challenge-02/evaluation.json), [8 bookkeeping cases](luna-bookkeeping-01/evaluation.json). The combined 68 cases contain 30 expected allows, 28 denies, and 10 clarifications. All were matched in this run. These remain small, synthetic developer-authored tests; the observed live failure illustrates why they must not be promoted into a general accuracy claim.

**Terra was not rerun on this revised extraction prompt.** The earlier Luna/Terra tie remains a result for the earlier saved prompt and policies. It is not a claim that the updated extractors have been compared. NotebookLM remains deferred while the user's Google verification is processed.

## Observed timing

| Live request | End-to-end observed time |
|---|---:|
| Raw HeelCode initial codeword storage | 7.743 s |
| Raw HeelCode follow-up in restarted process | 4.669 s |
| Raw HeelCode independent-session check | 4.877 s |
| Guarded initial request, successful continuity run | 14.900 s |
| Guarded follow-up on the same open pipe | 12.838 s |
| Guarded follow-up after bridge restart | 13.004 s |
| Exact replay of the third guarded answer | 0.003 s |
| Guarded independent-session check | 11.415 s |

Bridge times include classification plus answer inference and local work. These few observations are not a throughput benchmark. The revised batch extractions took 35.474 s for 36 cases, 32.811 s for 24 cases, and 13.117 s for eight cases. Raw usage/timing metadata is preserved; no dollar cost is inferred from subscription use.

## Verification, provenance, and remaining limits

- `mvn -q package`: **19 tests passed** (12 engine, 7 bridge). [Engine output](session-tests/engine.txt), [bridge output](session-tests/bridge.txt).
- `bun typecheck` passed for the research scripts.
- Live classifier and answer calls used Luna. No Sol/Astra fallback or billing changes were made.
- The new continuity script collects prompts, classification outputs, sanitized inference events, timings, session state, and the JSONL transcript. Public copies exclude raw `.private` provider logs and lock files. Earlier runs and provider failures are retained.
- No production provider/session code was changed to make authentication succeed. The user reauthenticated, then real calls passed. The implementation change was the narrow classification-prompt clarification.
- Windows remains untested. There is no output-content classifier, global TUI gate, malicious-local-user protection, or guarantee of exactly-once remote execution after a crash. The durable pending-marker behavior and bounded history limits still apply.

This completes the currently requested Luna piping/session test milestone. The broader comparative research goal remains open for NotebookLM, independent instructor review/annotation, and a final consolidated report.

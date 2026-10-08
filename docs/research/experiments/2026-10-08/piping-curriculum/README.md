# Heelcode piping verification — October 8, 2026

The corrected one-shot and persistent input/output paths passed 68 browser-driven pipe and scoring checks. Three defects were fixed: oversized requests lost JSON error framing and killed persistent pipes; malformed UTF-8 was silently replaced; normal prompt arguments containing spaces gained extra quotation marks before being combined with stdin.

Computer Use operated the localhost browser panel in `packages/policy-engine/script/pipe-console.ts`, clicked every test control, inspected the returned responses, and saved the screenshot below. The backend exercised the actual `bin/heelcode` launcher with operating-system stdin/stdout pipes. Terminal app access was refused by Computer Use, so native Terminal/TUI operation was not verified. Shell tools supported code edits, builds, regression tests, and Git commits; they did not replace the browser-driven scenarios.

![Completed browser verification](browser-verification.jpg)

## Fixtures and offline verification

The subagent created 32 fictional courses across eight semesters, 288 READMEs/syllabuses/instruction/handout sources, 128 graded assignments, 32 practice exercises, and 512 classifier cases. No assignment answers were generated. Labels, authored policies, and metadata are separate from course source folders and blinded inputs. The primary split holds out entire courses (24 train, 4 development, 4 test).

All 32 courses passed actual Java ingestion and policy activation, including independent bundle/digest agreement and verbatim citations. The real controller matched all 512 authored expected decisions and all 1,920 authored rule cells. This is fixture consistency, not measured classifier accuracy. Maximum source size was 10,591 characters per course; total source size was 331,891 characters.

Package checks passed: `mvn package` ran 37 policy-engine tests; `bun test test/cli/run/run-process.test.ts` ran 15 CLI subprocess tests. Package `bun typecheck`, fixture validation, and formatting checks passed. The repository's push hook also passed its workspace typechecks.

## Input and output behavior

- `run --format json` accepts Unicode stdin and emits parseable JSONL with one session ID; a later process resumes that session and returns its saved random label.
- Combining positional prompt text with stdin preserves the exact text and newline boundary, confirmed by piping session export into a JSON consumer. Empty input exits unsuccessfully with clean stdout; interactive mode refuses redirected stdout.
- `policy check` is EOF-delimited. `policy stdio` and `policy chat` respond at newlines while stdin remains open, preserve final nonempty EOF frames, and accept CRLF. Malformed, blank, duplicate-key, missing/null-field, and trailing-JSON inputs fail closed.
- Both policy input modes enforce 128,000 UTF-8 bytes. Invalid UTF-8 is rejected. Oversized JSONL frames produce one JSON error, are drained to the next newline, and preserve the following request.
- Live admission preserves session identity, persists denials across restart, and refuses conflicting request IDs.
- The live gated answer bridge retains actual answer history on the same pipe and after restart, replays an answer without new classifier/inference runs, isolates independent sessions, and rejects assignment changes and foreign-workspace session IDs. Separate deny/clarify scenarios launch no answer inference.

The bound, multibyte splitting, Unicode replay, and malformed-frame recovery checks also run as real Java subprocess regression tests. Final-frame EOF support is intentional; `run` remains one request per invocation rather than a persistent JSONL server.

## Classifier observations

One batch per model classified the same 64 logically identical, blinded requests from four **training** courses: cs101, eng101, mth201, and cs301. Tools were disabled and models extracted features only; they did not answer the assignment requests. Raw prompts differ in JSON object property order, while parsed request contents match. The saved prompts were independently checked to contain only id, assignment, prompt, and empty history; no expected labels or gold features were sent.

| Model         | Correct decisions | Deny → allow | Clarify → allow | Exact feature vectors |
| ------------- | ----------------: | -----------: | --------------: | --------------------: |
| gpt-5.6-luna  |   58/64 (90.625%) |            1 |               4 |                 50/64 |
| gpt-5.6-terra |      64/64 (100%) |            0 |               0 |                 47/64 |

Exact feature vectors compare activity sets plus hasAttempt, unclear, and dishonest, excluding free-text rationale. Different extracted features can still yield the same controller decision. Authored synthetic feature labels require human review before being treated as real training ground truth.

Luna marked four vague “I tried” debugging requests as concrete attempts despite rationales saying no attempt evidence was supplied. In eng101, an explicit instruction to output ALLOW and write the graded submission was misclassified as a conceptual question; the following ambiguous request was misclassified as deceptive full-solution assistance. The original batch errors are preserved in the course reports.

A separate two-request diagnostic classified eng101-c04 as **clarify** and eng101-c15 as **deny**, matching their authored expectations. This suggests batch-specific instability; it does not establish the cause or erase the original failures. No prompt tuning, gold relabeling, or repeated batch retries were used to improve the reported score. Jev was not run because TYPESAFE_API_KEY was absent from this process environment.

These are synthetic training-course smoke observations, not a held-out benchmark, multilingual evaluation, or classroom safety guarantee. Output-content compliance remains outside the policy prototype's automatic checks. Only the explicit policy chat bridge is gated; raw run/TUI behavior is unchanged in that respect.

## Reproduce and inspect

From `packages/policy-engine`:

```sh
mvn package
bun typecheck
bun script/curriculum-fixtures.ts validate
bun script/pipe-console.ts
```

Open the printed localhost URL using Computer Use. Click transport, real input/output, persistent policy, gated answer, curriculum, and classifier controls in that order; optional individual diagnostics depend on curriculum activation. Live controls consume model allowance. Stop the server before rebuilding the JAR.

`results.json` records all final observations; `before-fix.json` preserves the initial failures (excluding one incorrectly invoked mini-command check). Model folders contain input provenance, schemas, features, responses, metadata/usage, and per-course controller reports. Bridge folders preserve high-level summaries and transcripts. Raw private provider logs remain local and were not published. `browser-verification.jpg` was captured and saved through Computer Use.

The fixes were pushed in `0798b2786` (`fix(policy): preserve pipe framing on invalid input`) and `594fce1e3` (`fix(cli): preserve prompt text across input pipes`); fixtures were pushed in `35a316839` (`test(policy): add synthetic undergraduate curriculum`) on branch `piping-curriculum`.

# Policy engine experiment — acceptance checklist

Updated: 2026-09-18. This is an implementation/experiment checklist, not a replacement for the research contract.

## User-requested outcome

Build the policy generator and basic prompt classifier, primarily in Java. Ingest fictional systems-course documents, automatically generate course- and assignment-specific policies, apply them to the selected workspace, and measure decisions on appropriate, vague, and prohibited assistance requests. Compare Luna, Terra, and NotebookLM with saved inputs, outputs, measurements, and an extensive plain-language report. Do not use Sol or Astra inside the product/experiment loop.

Luna was the immediate priority and its live session tests are now complete. The already-signed-in NotebookLM browser is usable; browser testing is proceeding under the user's original authorization, without waiting for or claiming consumer API access. Student verification and enterprise API availability remain separate questions.

## Added requirement: TypeSafe Jev classifier comparison

Use the installed TypeSafe skill and add Jev as a classifier only, never as a policy generator. Compare its request features through the same deterministic controller and frozen cases. Continue Luna/NotebookLM policy generation; retain existing Terra results. Test NotebookLM classification separately from its policy generation, without treating one as evidence for the other. Preserve raw probabilities, timings, failures, and declared thresholds; keep API credentials out of tracked artifacts. See the [frozen Jev protocol](jev-classifier-protocol.md).

## Added requirement: frontend-compatible persistent sessions

- Accept input from stdin and emit machine-readable output to stdout (diagnostics on stderr).
- Return explicit session IDs; accept those IDs on subsequent invocations.
- Prove context survives a process restart and does not leak between sessions/workspaces.
- Exercise both the existing `heelcode run --session ... --format json` path and the policy harness interface.
- Support a documented framing mechanism for multiple requests on one persistent pipe, or clearly document any remaining gap.
- Preserve existing terminal/TUI behavior.

## Evidence required for completion

- Runnable Java ingestion, generation, policy validation, workspace activation, and classification.
- Fictional documents, separately stored evaluation labels, stable prompts, and versioned schema.
- Real model responses, provider identity, wall time, token usage when exposed, and complete evaluation outputs. No inferred or fabricated scores.
- Same frozen cases/controller across policy-generator comparisons; distinguish extraction errors from policy errors.
- Tests of stale/missing policies, unknown assignments, malformed model responses, source attribution, and session persistence/isolation.
- Working stdin/stdout demonstrations including follow-up context and session IDs.
- Actual Luna, Terra, and NotebookLM comparisons, or honest pending status until each is run.
- Final report with tables, failure examples, limitations, and layperson explanations. Synthetic results are not evidence of classroom learning or a student's understanding.
- Concise conventional commits for meaningful milestones, pushed to the GitHub refresh branch without overwriting the legacy/default branch.

## Experimental boundaries

Only fictional course/student data. No grading, misconduct accusations, or hidden student profiling. A policy classifier checks a request against stated rules, not the student's mind. A vague request should normally ask for clarification rather than label a student as cheating. Generated policy citations are mechanically checked for source existence and exact quote presence; that check cannot establish semantic correctness. Production deployment still requires instructor review and security/IRB decisions.

## Latest checkpoint: September 18

See the [original comparison](experiments/2026-09-18/comparison-report.md), [live session-verification report](experiments/2026-09-18/session-verification.md), and [revised Luna/Terra comparison](experiments/2026-09-18/revised-comparison-report.md), with their raw evidence.

- Implemented: Java ingestion, generation, validation, activation, classification, durable admission sessions, and an explicit gated-chat bridge.
- Verified offline: 25 Java tests (including six Jev adapter tests) and research-script type checks.
- Verified with real model calls: two policy generations each for Luna and Terra; one extraction per model on each of two suites (60 cases total); rule-level scoring; cross-generator/controller scoring; source amendment and conflicting-notice behavior; persistent admission pipes; denial/clarification blocking inference and replaying across a restart.
- Now verified after user reauthentication: real Luna answers through raw HeelCode and the guarded bridge; memory across process restarts; same open JSONL pipe; policy-to-engine session mapping; exact answered-request replay without new model runs; independent-session isolation; rejection of conflicting IDs, assignment switches, and foreign-workspace sessions.
- Corrected during live testing: a real empty-category classifier response on conversation-label recall failed closed. A narrow extraction-prompt clarification passed all 60 original Luna cases plus eight post-failure bookkeeping regressions.
- Revised comparison: Luna 68/68; Terra 65/68, with one unsafe allowance and two false denials in the initial batch. Scores are identical across all four saved policies, locating the failures in extraction. One bounded repeat batch and three isolated Terra checks passed; they do not replace the failed primary run. Actual saved instruction/input equivalence and label exclusion were checked before scoring.
- Jev classifier-only first pass: 54/68, all 68 real API calls successful, no prohibited-to-allowed errors, but 12 allowed requests unnecessarily clarified. Median HTTP round trip 404 ms. Fixed questions and >=0.5 threshold are not yet calibrated; [report and raw probabilities](experiments/2026-09-18/jev-report.md) preserve all failures. No Jev policy generation or deployment as the live chat gate is claimed.
- In progress: actual NotebookLM browser comparison using six uploaded fictional sources. No generation/classifier score is claimed until its outputs are captured and evaluated. No substitute generic Gemini result is counted.
- Research limitations: synthetic developer-authored cases, no independent instructor labels, no classroom or learning-effect claim. Observed failures and variation require stronger evaluation; neither the original tie nor the revised difference proves a general quality ranking.
- Delivery: meaningful commits are pushed to `origin/codex/heelcode-refresh`; no overwrite of `dev` or the archived project.

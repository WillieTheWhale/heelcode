# Policy engine experiment — acceptance checklist

Updated: 2026-09-18. This is an implementation/experiment checklist, not a replacement for the research contract.

## User-requested outcome

Build the policy generator and basic prompt classifier, primarily in Java. Ingest fictional systems-course documents, automatically generate course- and assignment-specific policies, apply them to the selected workspace, and measure decisions on appropriate, vague, and prohibited assistance requests. Compare Luna, Terra, and NotebookLM with saved inputs, outputs, measurements, and an extensive plain-language report. Do not use Sol or Astra inside the product/experiment loop.

Luna is the immediate priority. NotebookLM testing is deferred while the user completes Google's student verification. Consumer subscription access must not be represented as confirmed NotebookLM API access; official enterprise API availability is a separate question.

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

See the [comparison report](experiments/2026-09-18/comparison-report.md) and its raw evidence.

- Implemented: Java ingestion, generation, validation, activation, classification, durable admission sessions, and an explicit gated-chat bridge.
- Verified offline: 18 Java tests and research-script type checks.
- Verified with real model calls: two policy generations each for Luna and Terra; one extraction per model on each of two suites (60 cases total); rule-level scoring; cross-generator/controller scoring; source amendment and conflicting-notice behavior; persistent admission pipes; denial/clarification blocking inference and replaying across a restart.
- Still unverified: successful real HeelCode inference-session memory/isolation and the bridge's allowed-answer path. OpenAI authentication/connection errors and OpenCode Zen insufficient funds prevented completion. Controlled test solvers do not fulfill this requirement.
- Deferred: NotebookLM comparison pending the user's Google access. No substitute Gemini result is counted.
- Research limitations: synthetic developer-authored cases, no independent instructor labels, no classroom or learning-effect claim. The current tie is a reason to strengthen evaluation, not a basis for claiming equal real-world quality.
- Delivery: meaningful commits are pushed to `origin/codex/heelcode-refresh`; no overwrite of `dev` or the archived project.

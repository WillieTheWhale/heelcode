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

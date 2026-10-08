# Synthetic undergraduate curriculum fixtures

32 fictional courses across eight semesters: a computing major with mathematics, statistics, sciences, humanities, and social sciences. Each course has four distinct graded handouts and one ungraded practice exercise. No assignment is completed; no answer keys, real student records, or external datasets are included.

Run from packages/policy-engine:

```sh
bun script/curriculum-fixtures.ts generate
bun script/curriculum-fixtures.ts validate
bun typecheck
```

Generation is deterministic and rewrites only this fixture tree. The specification in the generator authors course tasks and permission profiles directly; it invokes no classifier, policy-generation model, or answer model. Synthetic authored policies are test oracles, not a claim that a real instructor reviewed them.

## File contract

- manifest.json contains relative course paths, assignment identifiers, counts, and splits. Its courses array exposes sourceDirectory, bundle, policy, oracle, cases, goldFeatures, and classifierInput for browser/CLI harnesses.
- COURSE/course contains exactly nine ingestible .md/.txt files: README, syllabus, shared instructions, a1–a4, practice, and a dated amendment. Only this folder is passed to Course.ingest. All metadata and labels are outside it.
- bundle.json is a Course record with sorted source IDs and SHA-256 hashes. Its digest uses courseId + LF + compact JSON of Source records in Java declaration order. The validator checks this derivation; CurriculumTest independently compares every bundle with real Java Course.ingest and scores all authored decisions and rule cells.
- policy.json is a complete fixture-authored Policy record, with verbatim source evidence for every rule and explicit permissions for all ten activities. It is intended for offline controller and transport checks, not evidence of model policy-generation accuracy.
- policy-oracle.json follows Evaluation.Oracle: scopes with allow and requireAttempt lists. Conflicts have explicit instructor/date precedence; the oracle contract supports allow/deny rules, not unresolved policy uncertainty.
- evaluation.json follows Evaluation.Cases exactly: {cases:[{id,assignment,prompt,expected,category}]}.
- gold-features.json follows Classifier.Batch exactly. It is scoring data only.
- classifier-input.json contains only {items:[{id,assignment,prompt,history:[]}]} and excludes decisions, categories, policy permissions, and gold features. Use it for direct classifier input; Evaluation.extract also strips labels from evaluation.json.
- metadata.json records synthetic authorship, dates, policy profile, assignment identities, and source limits.

## Coverage and splits

512 cases cover all ten activity types; concrete diagnostic observations versus vague claims of effort; mixed requests; graded and ungraded scopes; transparent disclosure versus explicit concealment; quoted deception and injected instructions; Unicode; dated withdrawal of a1 executable-test permission; a3 procedural exceptions; and unknown or ambiguous scope requests. Four permission profiles vary baseline computational help, a2 prose assistance or assessed test design, and a3 implementation permission. Assignment names, tasks, deliverables, observations, and interpretation requirements are course-specific. Common formatting and case families are deliberately repeated.

The primary split holds out whole courses: 24 train, 4 development, 4 test. The alternative assignmentHoldout split holds out a3 for development and a4 for testing across courses. Keep these benchmarks separate. Within-course wording and shared case templates can still leak across an assignment split; neither split demonstrates generalization to real curricula or languages. Do not tune on held-out cases and then present them as an untouched test.

Gold activities and expected decisions are authored, deterministic synthetic judgments, not empirically calibrated labels. They specify intended meanings in this controlled vocabulary; plausible human disagreements require review rather than relabeling a model result automatically. Permission profiles are simplified teaching policies. Quoted-string and limited Unicode examples do not establish multilingual robustness. The fixture contains no conversation-history labels; persistence tests need their own sequential scenarios.

# HeelCode: An Adaptive Educational Coding-Agent Harness

## Contract-level summary

HeelCode will be a course-configurable coding-agent harness for classes where AI use is permitted, rather than a detector intended to accuse students of cheating. It will mediate the student request, the agent's tool actions, and the generated response against an instructor-authored, assignment-specific policy. When a request would bypass an assignment's learning goal, it will provide an explanation and a smaller learning-oriented scaffold (for example, asking for an attempt, a prediction, or a test plan) instead of simply refusing.

The research question is whether this repeated, contextual scaffolding improves independent programming transfer while preserving useful AI assistance. The project will also test whether a small fine-tuned encoder can accurately and efficiently identify observable help-seeking patterns across unseen assignments and course levels.

## Problem and contribution

Students need experience using AI coding tools, but unrestricted answer generation can turn supported task completion into a misleading proxy for learning. The project deliberately does **not** claim to infer a student's private understanding or intent from a prompt. It detects observable interaction features and selects a reversible teaching action.

HeelCode's contribution is a full-agent intervention rather than a chat filter:

- **Course policy:** an instructor creates an assignment profile that describes learning objectives, allowed assistance, prohibited outcomes, protected files, and the maximum help level for each task.
- **Interaction-aware classification:** the system examines the request with the relevant assignment profile and a bounded recent session summary. It predicts assistance scope, evidence of prior effort, learning phase, and policy alignment.
- **Pedagogical controller:** an explicit policy maps those predictions to an intervention. The controller can allow, clarify, require an attempt/prediction/self-explanation, give a hint or decomposition, allow bounded tool use, or restrict a full solution.
- **Action and output control:** the policy applies to agent tools and generated code as well as the original prompt. A harmless-looking request cannot silently become a wholesale edit of a graded submission.
- **Student-facing appeal:** each intervention gives a plain-language reason and a way to revise the request. It is never a misconduct finding, is not used for grading, and does not report a student.
- **Local-first research posture:** study exports are opt-in and pseudonymous. The default product keeps session data local; exports minimize raw code and identifiers.

## Study questions and hypotheses

### RQ1 - Learning

Does assignment-aware adaptive scaffolding lead to stronger performance on a matched, AI-free programming transfer task than unrestricted use of the same coding agent?

**H1:** Students will show higher unassisted transfer accuracy and more complete reasoning/test explanations after the adaptive condition, even if the unrestricted condition is faster during the assisted task.

### RQ2 - Help-seeking behavior

Does the intervention increase observable learning-oriented behavior: supplying an attempt, articulating a hypothesis, predicting results, testing, and evaluating generated code?

**H2:** Adaptive sessions will contain more of those behaviors and fewer direct full-solution requests or unrestricted whole-assignment edits.

### RQ3 - Classifier reliability and feasibility

Can a compact encoder classify the observable help-seeking dimensions across unseen assignments and course levels with enough calibration, fairness, speed, and abstention behavior to be useful in the hybrid controller?

**H3:** A fine-tuned encoder will provide lower latency and comparable or better calibrated classification than a rubric-prompted LLM on held-out assignments, while routing ambiguous cases away from autonomous decisions.

## System design

### Policy authoring and schema

The local policy studio will create validated assignment profiles. Each profile will contain the assignment identifier and version, learning objectives, policy level/allowed help modes, prohibited outcomes, file and tool constraints, scaffold options, response disclosure requirements, and instructor-authored examples. It will support import/export, validation, and dry-run evaluation against sample student requests. It is not a central surveillance dashboard or LMS integration.

The policy is evaluated at three boundaries:

1. **Prompt admission:** decide whether to allow the request or ask for a productive reformulation.
2. **Tool action:** constrain file scope, edits, commands, tests, and actions that aggregate into a full solution.
3. **Output release:** keep the response within the assignment's assistance ceiling; replace solution leakage with an appropriate scaffold.

### Classifier and hybrid decision pipeline

The encoder takes the current request, concise assignment profile, and a bounded representation of recent interaction state. It is trained to label:

- assistance scope: concept, hint, critique, debugging, partial code, full solution;
- learner evidence: none, attempt, hypothesis, test, reflection;
- metacognitive phase: planning, implementation, monitoring/debugging, evaluation;
- policy alignment and recommended maximum assistance level.

The encoder is not the ultimate authority. A deterministic instructor policy chooses the intervention from these interpretable outputs. Below a calibrated confidence threshold, the system abstains and asks a clarifying question or routes to a more expensive structured-rubric LLM. This makes ambiguous requests a teaching conversation rather than a false positive.

Two portable backbones will be benchmarked: ModernBERT-base and DeBERTa-v3-small/base. The selection criterion is held-out reliability and local runtime cost, not headline benchmark score. A model is promoted to live use only when it meets the pre-registered thresholds below and can be exported to a portable local runtime; otherwise the hybrid remains rules plus rubric LLM.

### Data and annotation

The corpus will be built before any pilot-dependent training: instructor-authored and public seed examples, carefully adapted public data, and expert or trained-rater labels across multiple CS levels and different assignment policies. Consenting pilot traces may be a separately approved later source, never the prerequisite for the first model.

Each item includes the contextual policy and receives independent labels for the dimensions above plus a final action ceiling. The annotation process will use an adjudicated codebook, double-label a stratified subset, report agreement by dimension, and retain difficult/ambiguous examples as an explicit clarification/abstain class. CodeGuard is a valuable baseline and seed source, but not the whole corpus: its safety taxonomy deliberately combines academic-integrity requests with irrelevant and security-oriented requests.

All main splits must hold out whole assignments and, where possible, whole course levels. Random prompt splits are reported only as a secondary diagnostic because they leak assignment wording.

## Evaluation plan

### Offline system benchmark

Compare four decision systems on held-out course/assignment data:

1. deterministic instructor rules;
2. structured zero/few-shot rubric LLM;
3. fine-tuned encoder;
4. hybrid encoder plus deterministic policy plus abstention/fallback.

Report macro-F1 and per-class precision/recall; false allowance and false restriction rates; Brier score and expected calibration error; risk-coverage under abstention; end-to-end latency and memory; robustness to paraphrase and adversarial wording; and error disparity across available learner/course groups. Inspect false restrictions qualitatively because they are a direct student-experience cost.

**Promotion threshold:** before live deployment, the selected local encoder must beat rules-only on held-out macro-F1, meet a pre-registered safety recall threshold for prohibited full-solution requests, have acceptable calibration after temperature scaling, keep false restrictions below the instructor-approved ceiling, run locally within an interaction budget, and abstain rather than force a decision outside coverage. Exact numerical thresholds will be fixed with the mentor before training, not selected after inspecting results.

### Human-subject pilot

Subject to IRB approval and course access, run a low-stakes matched-task, counterbalanced crossover pilot. Each participant completes one task with unrestricted HeelCode and one matched task with adaptive HeelCode; task-condition order is randomized. Each assisted task is followed by an AI-free isomorphic transfer task and a brief explanation/test-plan prompt. Collect perceived helpfulness, friction, trust, and appeal/retry burden, with optional interview follow-up.

Primary outcome: AI-free transfer performance. Secondary outcomes: time, behavioral indicators, prompt revision success, tool-action patterns, perceived learning, and classifier/policy error rates. The analysis will report within-student estimates with order/task effects and confidence intervals. If IRB, recruitment, or course access does not permit a pilot, the guaranteed fallback deliverable is the full offline expert-labeled benchmark, usability review of the policy studio, and a workshop-ready design/evaluation paper that clearly labels the absence of causal classroom evidence.

No human-subject data is collected before approval. Participation is opt-in; the tool does not determine grades or trigger academic-misconduct processes.

## Semester milestones

| Weeks | Milestone |
| --- | --- |
| 1-2 | Narrow construct, write annotation codebook, select partner course/tasks, draft IRB/consent plan. |
| 3-4 | Build validated assignment-policy schema and policy-studio dry run; produce first labeled corpus and inter-rater pilot. |
| 5-7 | Implement prompt/tool/output controller and explanation/retry flow; benchmark rules and rubric-LLM baselines. |
| 8-9 | Fine-tune and calibrate encoder candidates; run assignment-held-out evaluations and error analysis. |
| 10-12 | Integrate only a threshold-passing local encoder; conduct usability study and, if approved, classroom crossover pilot. |
| 13-14 | Analyze data, finalize reproducibility package, workshop-ready paper, final demo, and presentation. |

## Deliverables

1. Policy schema, local authoring/validation/dry-run interface, and example assignment profiles.
2. Interception layer at prompt, tool-action, and output boundaries; explain/retry interface and durable local audit events.
3. Open, de-identified annotation codebook and an expert-labeled benchmark where consent and licensing permit release.
4. Encoder training/evaluation scripts, model card, calibration/abstention analysis, and rules/rubric/hybrid comparisons.
5. IRB-ready protocol and consent/data-management materials; pilot report if approved, otherwise documented offline/usability evaluation.
6. Workshop-ready paper, reproducibility checklist, final technical demo, and oral presentation.

## Curated reading program

The list below is intentionally ranked by evidence value rather than recency. Read the first nine in order before expanding to the technical or watch list.

### Essential evidence and design anchors

1. **Kazemitabaar et al., [CodeAid](https://arxiv.org/abs/2401.11314) (CHI 2024).** A 12-week deployment in a 700-student programming course with 8,000 interactions. Most relevant production precedent; study its non-solution help modes, student transparency, and deployment tradeoffs.
2. **Sheese et al., [Patterns of Student Help-Seeking](https://arxiv.org/abs/2310.16984) (2023).** 2,500 queries from a 12-week introductory programming deployment. Direct evidence for the help-seeking labels and why requests should not be judged from text alone.
3. **Kazemitabaar et al., [Cognitive Engagement Techniques](https://arxiv.org/abs/2410.08922) (IUI 2025).** Experiments with seven ways of adding productive friction to AI-generated code; the stepwise lead-and-reveal mechanism is the best direct scaffold inspiration.
4. **Bastani et al., [Generative AI Can Harm Learning](https://scale.stanford.edu/publications/generative-ai-can-harm-learning) (field experiment).** Nearly 1,000 students: supported performance and learning after AI removal can diverge. Justifies the AI-free transfer outcome.
5. **Xiao et al., [Transforming GenAI Policy to Prompting Instruction](https://arxiv.org/abs/2602.16033) (2026 preprint).** Large CS1 RCT (N=979). Strongest close competitor; prompting skill improved, but randomized groups did not significantly differ on final-exam performance. HeelCode must test repeated in-context intervention and independent transfer.
6. **Liffiton et al., [CodeHelp](https://arxiv.org/abs/2308.06921) (2023), plus [help-seeking analysis](https://arxiv.org/abs/2310.16984).** A small but authentic 12-week deployment that separates structural help from solution provision.
7. **Aleven and Koedinger, [An effective metacognitive strategy: learning by doing and explaining](https://onlinelibrary.wiley.com/doi/10.1207/S15516709COG2602_1) (2002).** Foundational evidence for self-explanation and transfer; use it to justify requiring a prediction or explanation, not merely blocking output.
8. **Singh et al., [Deferred AI Assistance](https://arxiv.org/abs/2604.19931) (2026 preprint).** Randomized graduate data-science study (N=97) in which writing a hint before seeing AI help produced the strongest hints. Direct support for “attempt first” scaffolds.
9. **Hou et al., [Personalized Parsons Puzzles](https://arxiv.org/abs/2501.09210) (2025).** Compared with showing full AI solutions, personalized scaffolded puzzles extended practice engagement.

### Classifiers, reliability, and data

10. **Raihan et al., [CodeGuard](https://aclanthology.org/2026.findings-eacl.48/) (Findings of EACL 2026) and [dataset/code](https://github.com/mraihan-gmu/CodeGuard).** Core encoder baseline: 8,000 prompts and a RoBERTa-base classifier. Use it, but audit the synthetic and broad safety taxonomy rather than treating its F1 as classroom validity.
11. **Liu et al., [Calibration of LLM-based Guard Models](https://proceedings.iclr.cc/paper_files/paper/2025/hash/a99f732df9b668284b449da0214a3286-Abstract-Conference.html) (ICLR 2025).** Shows why raw guard confidence is not sufficient; supports calibration and abstention requirements.
12. **Warner et al., [ModernBERT](https://arxiv.org/abs/2412.13663) (ACL 2025).** Candidate long-context, code-aware encoder; use only after comparison with a smaller baseline.
13. **He et al., [DeBERTa-v3](https://openreview.net/pdf?id=sE7-XhLxHA) (ICLR 2023).** Efficient, established encoder baseline for the fine-tuning study.

### Harness engineering and current technical context

14. **Yang et al., [SWE-agent](https://proceedings.neurips.cc/paper_files/paper/2024/hash/5a7c947568c1b1328ccc5230172e1e7c-Abstract-Conference.html) (NeurIPS 2024).** Demonstrates that the agent-computer interface materially changes agent behavior; this grounds a harness intervention rather than a standalone chatbot.
15. **Wang et al., [OpenHands](https://proceedings.iclr.cc/paper_files/paper/2025/hash/a4b6ad6b48850c0c331d1259fc66a69c-Paper-Conference.pdf) (ICLR 2025).** Reference architecture for tool runtime, sandboxing, event logs, and agent evaluation.
16. **[DeepSeek Harness architecture](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md) and [official preview](https://deepseek.com/harness/en/).** Technical documentation, not learning evidence. Useful for its plugin/service/event composition and explicit lifecycle boundaries. Treat the fast-moving developer preview as inspiration, not a stable dependency.
17. **Zhong and Zhu, [AI Harness Engineering](https://arxiv.org/abs/2605.13357) (2026 preprint).** Useful vocabulary for observability, permissions, verification, and intervention recording. It is a conceptual position paper with limited controlled validation, so do not rely on it for efficacy claims.

### Watch list - useful but weaker evidence

- **[EduGuard](https://arxiv.org/abs/2607.15738) (2026 preprint):** valuable architectural comparison (course retrieval, rubric control, verifier); only a 10-student pilot, so do not claim its learning results generalize.
- **[Scaffolding Metacognition in Programming Education](https://arxiv.org/abs/2511.04144) (2025 preprint):** a large log analysis that motivates planning/monitoring/evaluation labels; inspect methods before treating its inferred construct as ground truth.
- **[ACM GenAI and Programming Assessment Task Force report](https://acm-education-genai-task-force.github.io/ACM_Taskforce_GenAI_Report_16Feb26.pdf) (2026):** strong professional context for assignment-specific rules and comprehension checks, but not causal research.
- **[AI Assessment Scale](https://arxiv.org/abs/2412.09029) (2024):** useful policy vocabulary; adapt the scale to concrete learning objectives instead of using levels as a complete specification.

## Novelty statement for proposals and abstracts

Existing work has evaluated AI coding assistants that avoid direct solutions, taught students learning-oriented prompting, or trained prompt safety classifiers. HeelCode combines these strands in a course-configurable **agent-level** system: it regulates requests, tool actions, and outputs using assignment policy; its small encoder predicts interpretable help-seeking dimensions and abstains under uncertainty; and its effectiveness is evaluated by unassisted programming transfer rather than prompt quality or supported completion alone. The study contributes a reusable policy/benchmark/controller design and, if approved, a rigorous within-student pilot.

## Scope and ethics boundaries

- In scope: low-stakes, AI-allowed coding tasks; transparent scaffolding; local policy authoring; voluntary research participation; assessment of learning and system reliability.
- Out of scope: automated misconduct determination, surveillance dashboards, grade recommendations, hidden student profiling, claims of mind-reading, or a general-purpose detector of unauthorized external AI use.
- Any use of student records, session logs, or research recruitment requires mentor/institutional review and IRB approval before collection.

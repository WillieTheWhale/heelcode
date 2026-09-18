# Final verification — September 18, 2026

Executed from `packages/policy-engine` unless noted otherwise:

- `JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home mvn -q test`: 25 tests, zero failures/errors/skips (12 EngineTest, seven BridgeTest, six JevModelTest).
- `bun typecheck`: passed after all three NotebookLM research scripts were added.
- `script/prepare-notebook-classifier.ts`: rerun into a new temporary output directory; all three source sets prepared (36/24/8), common browser prompt 2,857 characters.
- `script/compare-jev.ts`: offline replay into a new temporary output directory reproduced 54/68 under all four correct policies, with complete input/model/probability provenance retained. A separate existing-output invocation was refused as intended.
- `script/compare-notebook-policies.ts`: offline replay reproduced 49/50 rule settings for each draft and all classifier cross-product scores.
- `script/compare-notebook-classifier.ts`: offline replay verified uploaded-source contents against unlabeled fixtures, actual browser prompts and captured output checksums, then reproduced 60 correct available decisions under the four correct policies plus eight unavailable cases. The empty batch was rejected by Java with `Missing, extra, or duplicated evaluation IDs`; it was not scored as a semantic zero or silently filled in.
- From the repository shell, `heelcode --version` exited successfully with `local`, and `heelcode policy --help` listed the Java commands including `extract-jev`.
- `git diff --check` passed. Local Markdown links in the consolidated/Jev reports, protocols, and package README resolved.
- Credential-shaped content scan passed across the policy package and Jev artifacts; a separate scan of 78 NotebookLM evidence files found no API key, signed-in account email, private notebook URL, or account-selector query parameter.

These checks did not make new inference calls. The final offline replays reused the preserved primary model outputs, including failures. Live session evidence was already verified separately in [the session report](session-verification.md).

The repository pre-push hook also runs its package typechecks. Commits are pushed only to `origin/codex/heelcode-refresh`. Existing unrelated TUI/branding and research-document work is preserved outside these commits.

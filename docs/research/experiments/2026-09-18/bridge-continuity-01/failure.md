# Test-runner assertion failure

The first continuity run completed three real answered requests. The saved transcript shows exact random-label recall on the same pipe and after a bridge restart. It then replayed the third response identically, without new model-run directories.

The script stopped at the conflicting-request-ID assertion because it looked for the explanatory refusal text in the `error` field. `Main.reply` correctly emitted the exception type in `error`, the explanation in `message`, and `forward:false`. The immediately following assignment-change request was also correctly refused. Neither failure required a production-code fix.

The assertion was corrected to inspect `message`. `bridge-continuity-02` is a fresh complete run, not an edited replacement. This first run did not reach its independent-session or foreign-workspace checks and must not be reported as a full pass. Its raw provider logs remain in the ignored local workspace; the saved transcript is retained here.

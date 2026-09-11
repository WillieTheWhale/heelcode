# HeelCode fork baseline

The refreshed HeelCode starts from `anomalyco/opencode` branch `dev`, commit `95daf90670b7c039c436c85537da5fbfe2205b41` (September 11, 2026).

`origin` remains the HeelCode repository. `upstream` points to `https://github.com/anomalyco/opencode.git`. Development is on `codex/heelcode-refresh`; the original `main` history remains available locally and remotely.

The initial changes restore the animated HeelCode terminal wordmark, Carolina-blue dark/light theme, default theme selection, and terminal window title. Provider configuration and the engine use upstream OpenCode. The previous PromptLab transport and authentication integration are archived, not carried into this baseline. Existing user-selected themes still take precedence over the new default.

Run from the repository:

```sh
bun install
bun dev
```

For direct terminal use, link the source launcher into a directory on PATH:

```sh
chmod +x bin/heelcode
ln -sfn "$PWD/bin/heelcode" "$HOME/.local/bin/heelcode"
```

Then run `heelcode` from any project directory in Terminal or Ghostty. The launcher preserves the current directory, forwards arguments, and runs the current checkout with Bun. It does not require a compiled release after each UI edit.

Research notes and papers remain under `docs/research`. The original project archive is beside this repository at `../heelcode-archive-2026-09-11.egaaST`; its README describes restoration of the Git history and working files.

This refresh does not yet implement the educational policy engine, classifier, shared harness server, or additional CLI protocol. Upstream package names, configuration paths, and API identifiers retain their OpenCode names for compatibility.

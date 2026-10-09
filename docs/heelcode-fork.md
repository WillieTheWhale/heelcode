# HeelCode fork and packaging

The refreshed HeelCode originally started from `anomalyco/opencode` branch `dev`, commit `95daf90670b7c039c436c85537da5fbfe2205b41` (September 11, 2026).

On October 9, 2026, HeelCode merged the latest upstream `dev`, commit `388406238bd5ca15564a762840a2362c3a45bd9c` (October 8, 2026), on `update-opencode`, preserving the work from `piping-curriculum`. This imports 164 upstream commits, including provider, authentication, terminal, subprocess, and macOS binary-signing fixes.

`origin` remains the HeelCode repository. `upstream` points to `https://github.com/anomalyco/opencode.git`. OpenCode's integration branch is `dev`; this fork currently has `main` and the curriculum branches. The original HeelCode history remains available locally and remotely.

The initial changes restore the animated HeelCode terminal wordmark, Carolina-blue dark/light theme, default theme selection, and terminal window title. Provider configuration and the engine use the OpenCode source contained in this repository. The previous PromptLab transport and authentication integration are archived, not carried into this baseline. Existing user-selected themes still take precedence over the new default.

Run from the repository:

```sh
bun install --frozen-lockfile
bun dev
```

For direct terminal use, link the source launcher into a directory on PATH:

```sh
chmod +x bin/heelcode
ln -sfn "$PWD/bin/heelcode" "$HOME/.local/bin/heelcode"
```

Then run `heelcode` from any project directory in Terminal or Ghostty. The launcher preserves the current directory, forwards arguments, and runs the current checkout with Bun. It does not require a compiled release after each UI edit.

Research notes and papers remain under `docs/research`. The original project archive is beside this repository at `../heelcode-archive-2026-09-11.egaaST`; its README describes restoration of the Git history and working files.

The educational policy engine, classifier adapters, admission bridge, and curriculum fixtures now live in `packages/policy-engine`. Its Java implementation and `heelcode policy` entry point are preserved by the upstream merge. Upstream package names, configuration paths, and API identifiers retain their OpenCode names for compatibility.

## Packaging from this source tree

HeelCode contains all tracked files from the merged OpenCode tree, with fork changes applied in place. It does not use a Git submodule, a sibling OpenCode checkout, or a globally installed `opencode` executable for its engine. Workspace dependencies link the local Core, Protocol, Server, SDK, and TUI packages. The desktop server also builds from `packages/opencode`; its development v2 CLI now builds from `packages/cli` instead of downloading a published upstream CLI. That CLI carries the build version so desktop updates stage the current executable. Its background daemon uses a separate `heelcode` state directory rather than adopting a service from an installed OpenCode instance.

Build the current platform's executable and embedded web UI:

```sh
bun install --frozen-lockfile
bun run build:heelcode --single --skip-install
```

The result is `packages/opencode/dist/heelcode-<platform>/bin/heelcode` (with an `.exe` suffix on Windows). The executable includes the local engine, terminal UI, web assets, and Bun runtime. It can run outside this checkout without Bun or OpenCode on PATH. Ordinary provider credentials and network access are still required for model calls. Source-launcher and compiled HeelCode automatic upstream upgrades are disabled so an OpenCode release cannot replace the fork automatically.

Use `--skip-embed-web-ui` for a smaller terminal-only build. To build all supported targets, omit `--single` and `--skip-install`; the existing upstream build script installs the platform dependencies. Distribute the repository's `LICENSE` with each executable. macOS distribution still needs release signing/notarization, and Windows signing and classroom installation remain release tasks.

The native executable covers the coding engine. The separate `heelcode policy` wrapper requires the shaded policy-engine JAR built with `mvn package` and a Java 17+ runtime. The research classifiers also have their own service/authentication requirements. The native binary alone does not package that Java wrapper or enforce a course policy by default. See [the policy-engine README](../packages/policy-engine/README.md) before assembling a student distribution.

The October 9 update was verified on macOS arm64: 889 tests passed across the changed upstream engine tests, CLI pipe tests, theme test, CLI API tests, and Java policy engine. Type checks passed in OpenCode, Core, Server, TUI, CLI, App, Desktop, and Policy Engine. Client generation produced no changes. Both the standalone executable with embedded web UI and the Electron desktop build succeeded. With PATH restricted to `/usr/bin:/bin`, the standalone executable served its health endpoint and embedded HTML, and the locally built v2 CLI started an isolated daemon, returned its credential, and passed an authenticated `/api/health` check. Each temporary server was stopped after verification. Other platforms and signed installers have not been validated by this synchronization.

## Updating OpenCode again

Start from the HeelCode branch whose changes should be preserved, then merge the upstream integration branch:

```sh
git fetch upstream dev
git merge --no-ff upstream/dev
bun install --frozen-lockfile
```

Resolve overlaps by preserving HeelCode behavior while incorporating upstream changes. Run type checks and tests from the affected package directories, then rebuild the HeelCode executable. Update the upstream commit recorded here after each synchronization.

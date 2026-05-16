# Use Cases

Status ledger for use cases under `use-cases/`. Machine-maintained — the `define-use-case` skill appends rows; the dev-team orchestrator updates the `Status` and `Updated` columns as it works. Do not hand-edit those two columns unless you know why; edit the use-case file or re-run the skill instead.

Statuses:
- `pending` — saved but not yet picked up by the dev-team
- `in-progress` — the dev-team has started analysis
- `done` — implementation and tests completed
- `blocked` — the dev-team escalated (6-round cap hit, user abort, or infeasibility)

| # | File | Title | Status | Updated |
|---|------|-------|--------|---------|
| 01 | [use-cases/01-daemon-and-cli-mcp-query-api.md](use-cases/01-daemon-and-cli-mcp-query-api.md) | Daemon + CLI/MCP query API over the call-graph indexes | done | 2026-05-15 |
| 02 | [use-cases/02-build-and-release-pipeline.md](use-cases/02-build-and-release-pipeline.md) | Build & release pipeline via GitHub Actions | done | 2026-05-15 |
| 03 | [use-cases/03-embed-build-version-at-runtime.md](use-cases/03-embed-build-version-at-runtime.md) | Embed build version into runtime constants | done | 2026-05-15 |
| 04 | [use-cases/04-auto-watch-incremental-rebuild.md](use-cases/04-auto-watch-incremental-rebuild.md) | Auto-watch and incremental rebuild of the in-memory index | done | 2026-05-16 |

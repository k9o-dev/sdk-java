# Changelog — dev.k9o:core (Java)

## 1.4.0 (unreleased)

### Added

- `ai().generate`: run a prompt through an LLM, with an optional `model`. Same result as `compute().llms().invoke`.
- `streams()`: `create`, `list`, `get`, `delete`, `publish`, `replay` and `subscribe` for user data streams. `subscribe` reconnects from the last sequence.
- `query(sql, params)`: run one read-only SQL statement. `params` is a `List` (positional) or a `Map` (named `:name` placeholders, rewritten to `$n`).
- `search(collection, input)`: full-text search over a text column, best matches first, with `limit` and `cursor`.
- `from(c).select().textSearch` and `vectorSearch`: full-text and nearest-neighbour search on the select builder.
- `approvals()`: `list`, `get`, `approve`, `reject` and `bulk` for the pending-change queue. `approve` can answer with `status` still `pending_approval`; `list` is not paginated; `bulk` skips failed rows silently, so compare `affectedIds` with what you intended.
- `approvalPolicies()`: `list`, `create` and `delete` for the rules that decide which writes are queued.
- `schema().collections().list(limit, cursor)`: list the project's collections by name, with `meta().cursor().next()` for the next page.

### Changed

- README description.
- Function deploy examples use the sandbox tier.

## 1.3.0 — 2026-10-26

Every method now matches the gateway and is checked by a contract test.

### Changed

- `projects().create` takes `Projects.CreateInput` with `displayName` (required) and optional `slug`, `region`, `custodyMode`. `get` and `update` use the projects routes.
- `functions().deploy` takes `name`, `runtime`, `tier` (`gateway` or `sandbox`), `artifactKey`, `version` and `description`. Inline code is gone.
- `functions().invoke` sends `{payload}` and accepts `Functions.InvokeOptions`. `functions().list` accepts `limit` and `cursor`.
- Policies `create`, `update` and `validate` take a `Policies.PolicyInput` document. `policies().list` takes only `status`.
- `audit().query` filters are `eventType`, `actorId`, `after`, `before`, `limit`, `cursor`.
- Role grants use `/role-grants` and identity providers use `/identity-providers`, with a typed `CreateIdpInput`.
- `org().defaultLLM()` returns an object. `org().smtp().set` takes `passwordRef` and `fromAddress`; `test` takes `to` and `subject`.
- `me().apiTokens().create` takes `label`, `scopeManifest` and `ttlSeconds`. `me().sessions().revoke` and `revokeAll` use the session routes.
- `compute().llms().invoke` parameters are flat and the result has `content` and `usage`. Stream chunks are OpenAI-style.
- `RealtimeEvent` is `{topic, eventType, payload, occurredAt}`. `studio().workflows().trigger` sends `{triggerContext}`.
- Optional fields left `null` are no longer sent.
- The API host is read from the base URL: any `<org>.api.<apex>` host works and no domain is built in.

### Removed

- `gate().verifySession`, `gate().hosted`, `realtime().replay` and `realtime().subscribePoll`.
- `me().notifications()`, `me().auditLog()`, `me().dataExport()`.
- `org().groups().create`, `org().groups().delete`, `org().members().get`, `org().smtp().get`.
- The `modality` filter of `compute().llms().list` and the `triggerType` filter of `studio().workflows().list`.

### Added

- `functions().uploadArtifact(bytes, "wasm" | "tar")` returns the `artifactKey` for `deploy`.

## 1.2.0 — 2026-10-25

- The README now introduces the Kilimanjaro building blocks (Keep, Pulse, Gate, Kibo). No change to the public API.

## 1.1.1 — 2026-10-25

- Documentation links corrected: the documentation hub is https://docs.kilimanjaro.dev, with the website, platform API and console listed in a Links section. Package metadata points at the same places.

## 1.1.0 — 2026-10-25

- Documentation rewritten with runnable examples and expected output; internal cross-references removed from the published sources. No change to the public API.
- Error text for `update()` / `delete()` without an `id` filter no longer mentions a version or a roadmap.

## 1.0.0 — 2026-10-23

First public release. See the README for the full API overview.

# dev.k9o:core

Client for Kilimanjaro. Read and write your data, store files, manage policies and migrations,
deploy and invoke agents or functions, and publish to and subscribe to streams and realtime events.

## Contents

- [Links](#links)
- [Install](#install)
- [Quick start](#quick-start)
- [Authentication and project context](#authentication-and-project-context)
- [Running queries](#running-queries)
- [Query with SQL](#query-with-sql)
- [Search](#search)
- [Deploying a function](#deploying-a-function)
- [Streams](#streams)
- [Approvals](#approvals)
- [Approval policies](#approval-policies)
- [Schema collections](#schema-collections)
- [Generate text](#generate-text)
- [Handling errors](#handling-errors)
- [Appendix](#appendix): [about Kilimanjaro](#about-kilimanjaro)

## Links

- Website: <https://kilimanjaro.dev>
- Documentation / Developer Hub: <https://docs.kilimanjaro.dev>
- Platform API: <https://api.k9o.io>
- Platform Console: <https://console.k9o.io>

## Install

Maven:

```xml
<dependency>
    <groupId>dev.k9o</groupId>
    <artifactId>core</artifactId>
    <version>1.4.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'dev.k9o:core:1.4.0'
```

Requires JDK 21 or newer. See [requirements](#requirements).

## Quick start

The examples on this page use a `tasks` collection with `title` and `done` fields.

```java
import dev.k9o.Envelope;
import dev.k9o.K9o;

import java.util.List;

public class Example {
    public record TaskItem(String id, String title, boolean done) {}

    public static void main(String[] args) {
        K9o.K9oClient k9o = K9o.createClient(K9o.Opts.builder()
            .orgSlug("acme")
            .projectSlug("tasks-app")
            .apiKey(System.getenv("K9O_API_KEY"))
            .build());

        Envelope<List<TaskItem>> result = k9o.from("tasks")
            .select()
            .eq("done", false)
            .limit(10)
            .execute(TaskItem.class);

        for (TaskItem task : result.data()) {
            System.out.println("Output: " + task.id() + " " + task.title());
        }
    }
}
```

```text
Output: tsk_01 Write docs
Output: tsk_02 Ship 1.0
```

## Authentication and project context

Every request carries your API key. The organization and project tell the platform where to run it.

- `apiKey` is sent as `Authorization: Bearer <key>`.
- `orgSlug` builds your organization's host on the default platform.
- `projectSlug` selects the project for data, auth and storage calls.
- The SDK does not read environment variables. Read the key yourself, as in the quick start.

The quick start uses the default platform. For a self-hosted or internal gateway, pass its organization host as `baseUrl`.
Any `<org>.api.<apex>` host works, and no domain is built in. Leave out `orgSlug`:

```java
K9o.K9oClient k9o = K9o.createClient(K9o.Opts.builder()
    .baseUrl("https://acme.api.corp.example")
    .projectSlug("tasks-app")
    .apiKey(System.getenv("K9O_API_KEY"))
    .build());
```

See [client options](#client-options) for the full list.

## Running queries

Every call returns an `Envelope<T>`. Read rows with `data()` and paging details with `meta()`.

```java
Envelope<List<TaskItem>> page = k9o.from("tasks").select().limit(2).execute(TaskItem.class);
String next = page.meta().cursor().next();
System.out.println("Output: next cursor: " + next);
```

```text
Output: next cursor: cur_abc
```

Pass the cursor back with `.cursor(next)` to get the next page. `meta().cursor()` is `null` when there is no cursor.

Insert, update and delete use the same builder:

```java
Envelope<TaskItem> created = k9o.from("tasks")
    .insert(Map.of("title", "Write docs", "done", false), TaskItem.class);
String id = created.data().id();

k9o.from("tasks").update(Map.of("done", true)).eq("id", id).execute(TaskItem.class);
k9o.from("tasks").delete().eq("id", id).execute(Map.class);
```

- `update` and `delete` need an `.eq("id", ...)` filter, or they throw `K9O_INVALID_FIELD_VALUE`.
- Filters available: `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `like`, `ilike`, `is`, `in`, `notIn`.
- Use `filter(column, op, value)` for any other operator.

Other areas of the platform are listed in [methods by namespace](#methods-by-namespace).

## Query with SQL

`query` runs one read-only SQL statement and returns the rows in `data()`. Values are bound by the database, never put into the SQL text.

```java
Envelope<List<Map<String, Object>>> res = k9o.query(
    "SELECT id, title FROM tasks WHERE done = :done AND owner = :owner LIMIT 2",
    Map.of("done", false, "owner", "ada"));
System.out.println("Output: " + res.data().size() + " rows");
System.out.println("Output: " + res.data().get(0).get("title"));
```

```text
Output: 2 rows
Output: Write docs
```

- Pass a `Map<String, Object>` for named values (`:name` in the SQL) or a `List<Object>` for positional values (`$1`, `$2`). A list is sent as written.
- Values are strings, numbers, booleans or `null`. Anything else throws `K9O_INVALID_FIELD_VALUE`: serialise it to JSON text first.
- A `:name` with no value in the map throws `K9O_INVALID_FIELD_VALUE` before any request is sent. Text inside quotes, comments and `::` casts is left alone.
- Read-only: a write is refused with `K9O_RAW_SQL_READ_ONLY`; change data with `from(...)`. One statement per call, at most 100000 characters of SQL and 1000 parameters, row-level security applies, and the caller needs the project-editor role.
- `dataset.<name>` in the SQL reads an analytics dataset.

## Search

`search` finds rows whose text column matches a query, best matches first.

```java
Envelope<List<Map<String, Object>>> hits = k9o.search("articles",
    new Search.Input("body", "solar power", null, null, 5, null));
System.out.println("Output: " + hits.data().get(0).get("title"));
```

```text
Output: Solar power basics
```

`Search.Input(column, q, language, type, limit, cursor)`: `column` and `q` are required, `null` fields are not sent. `language` defaults to `english`; `type` is `plain` (default), `phrase` or `websearch`. Page with `cursor`.

The same search is available on the select builder and composes with filters, `limit` and `cursor`. `vectorSearch` returns the rows nearest to an embedding, closest first:

```java
var near = k9o.from("articles").select()
    .eq("lang", "en")
    .vectorSearch("embedding", List.of(0.12, -0.4, 0.9), new dev.k9o.data.FromBuilder.VectorSearchOptions("cosine", 3))
    .execute(Map.class);
System.out.println("Output: " + near.data().size() + " rows");
```

```text
Output: 3 rows
```

- `textSearch(column, query)` or `textSearch(column, query, new dev.k9o.data.FromBuilder.TextSearchOptions(language, type))`.
- `vectorSearch(column, embedding)` or with `VectorSearchOptions(metric, limit)`: `metric` is `cosine` (default), `euclidean` or `dot`; `limit` is 1 to 1000.
- A bad column name, an empty query or embedding, or an out-of-range value throws `K9O_INVALID_FIELD_VALUE` before any request.

## Deploying a function

Upload the compiled artifact first. `uploadArtifact` returns the `artifactKey` that `deploy` needs.
The kind is `"tar"` (an OCI image archive of the function's container image, up to 256 MiB) and the tier is `sandbox`. The `gateway` tier is for functions owned by platform operators and is not available to most projects. Sandbox functions run in a [Firelock](https://github.com/firelock-sandbox/firelock) microVM sandbox.

```java
byte[] archive = Files.readAllBytes(Path.of("hello.tar"));
Functions.Artifact artifact = k9o.functions().uploadArtifact(archive, "tar").data();

k9o.functions().deploy(new Functions.DeployFunctionInput(
    "hello", "rust", "sandbox", artifact.artifactKey(), "1.0.0"));

Map<String, Object> result = k9o.functions().invoke("hello", Map.of("name", "Ada")).data();
System.out.println("Output: " + result);
```

```text
Output: {greeting=Hello, Ada}
```

`invoke` sends your payload as `{payload}`. Pass `Functions.InvokeOptions` as a third argument for `background` and similar controls.
Optional fields left `null` are not sent.

## Streams

Create a stream, publish an event and read it back live. `maxBytes` is the storage ceiling in bytes: 1, 2, 4, 8, 16, 32 or 64 GiB.

```java
Streams.UserStream stream = k9o.streams().create("Orders", 1L << 30, null).data();

k9o.streams().publish(stream.slug(), Map.of("id", 1, "total", 42), null);

try (var events = k9o.streams().subscribe(stream.slug(), null)) {
    for (Streams.StreamEvent event : events) {
        System.out.println("Output: " + event.sequence() + " " + event.data());
        break;
    }
}
```

```text
Output: 1 {id=1, total=42}
```

- `subscribe` returns an `Iterable` you close when done. Pass a sequence as the second argument to resume after it.
- `replay` reads a bounded range in one call: `k9o.streams().replay(slug, null, null, 100)`.
- The server closes each live connection after a short time.
  The subscription reconnects from the last sequence it delivered, so no event is repeated or skipped.
- Publishing needs the project admin role. Subscribing and replaying need admin or auditor.
- While streams are not available, calls fail with `K9O_STREAM_UNAVAILABLE` (HTTP 503).
  A rejected request, such as a bad `subject`, fails with `K9O_STREAM_INVALID`.

## Approvals

Writes sent with `requireApproval` can wait in a queue for a second person. `k9o.approvals()` reads that queue and decides its changes.
Project admins and auditors see every change; other members see only the ones they proposed.

```java
var queue = k9o.approvals().list("pending_approval", 20).data();
Approvals.PendingChange change = queue.get(0);

System.out.println("Output: " + change.id() + " " + change.operation() + " " + change.collection());

var decided = k9o.approvals().approve(change.id(), "checked the amount").data();

System.out.println("Output: " + decided.status());
```

```text
Output: apr_01j9x3 update orders
Output: redeemed
```

- A change is `pending_approval`, `approved`, `rejected`, `expired` or `redeemed` (applied). An `expired` change can still read `pending_approval` until someone decides it.
- `approve` can answer 200 with `status` still `pending_approval`. That means your approval is recorded and more approvers are needed; the change is not applied yet.
- `reject(id, reason)` needs a non-empty `reason`; the SDK checks it before sending.
- `list(status, limit)` is not paginated: it returns the newest `limit` changes (1 to 200, default 50). `get(id)` reads one.
- Deciding needs the queue-admin role (project admin or auditor).

Decide many at once with `bulk`. `criteria` is a predicate over each pending change (see [Approval policies](#approval-policies)):

```java
var run = k9o.approvals().bulk("reject", Map.of("eq", List.of("change.table", "orders")), "stale").data();

System.out.println("Output: " + run.affectedCount() + " " + String.join(",", run.affectedIds()));
```

```text
Output: 2 apr_01j9x3,apr_01j9x4
```

- A row that fails (a conflict, an apply error) is skipped silently and left out of `affectedIds`. Compare `affectedIds` with the set you intended.
- `reject` needs a `reason`; `criteria` must be a predicate object. `attestationId` identifies the batch, even when nothing matched.
- It considers the newest 10000 pending changes. Bulk approve is not guaranteed to apply every matched row.

## Approval policies

A policy decides what happens to writes on one collection that match a predicate: `queue` them for approval, or `auto_approve` them.
For a write the first matching policy, oldest first, decides. Policies apply to writes sent with `requireApproval`.

```java
var policy = k9o.approvalPolicies().create(new ApprovalPolicies.CreateInput(
    "orders", "update", "queue", Map.of("gt", List.of("change.row.amount", 1000)))).data();

System.out.println("Output: " + policy.id() + " " + policy.action());

var removed = k9o.approvalPolicies().delete(policy.id()).data();

System.out.println("Output: " + removed.id());
```

```text
Output: apo_01j9x5 queue
Output: apo_01j9x5
```

- `operation` is `insert`, `update` or `delete`; `action` is `auto_approve` or `queue`.
- `collection` matches `^[a-zA-Z_][a-zA-Z0-9_]{0,62}$` and `predicate` is a non-null object. The SDK checks both before sending.
- A predicate has one operator key: `and`, `or`, `not`, `eq`, `neq`, `gt`, `gte`, `lt`, `lte`, `in`, `not_in`, `in_cidr`, `not_in_cidr` or `contains`.
  `and` and `or` take a list of predicates, `not` takes one, and a comparison takes `[left, right]`.
- Operands start with `maker.` or `change.` (for example `change.row.amount`, `change.table`, `change.operation`, `maker.id`).
- `list()` returns all policies. Identical policies are not de-duplicated. Creating and deleting need project admin or author.

## Schema collections

`k9o.schema().collections().list` lists the project's collections by name, with their size and last migration.

```java
var page = k9o.schema().collections().list(2, null);

for (Schema.CollectionSummary c : page.data()) {
    System.out.println("Output: " + c.name() + " " + c.rowCount() + " rows, " + c.columnCount() + " columns");
}
System.out.println("Output: next " + page.meta().cursor().next());
```

```text
Output: orders 120 rows, 8 columns
Output: tasks 14 rows, 4 columns
Output: next dGFza3M
```

- Pass `meta().cursor().next()` as `cursor` for the next page; it is `null` on the last page. `limit` is 1 to 200 (default 50).
- A row has `name`, `rowCount`, `rowCountIsExact`, `columnCount`, `indexCount`, `lastMigrationSlug` and `lastMigrationAppliedAt`.
- `rowCount` is a live count that skips soft-deleted rows and ignores row-level security. Without an `org_id` claim on the token, `meta().warnings()` says so.
- Project admins, auditors, editors, members and viewers can read it.

## Generate text

`ai().generate` runs a prompt through an LLM. Only the prompt is required.

```java
Compute.LLMResponse res = k9o.ai()
    .generate("Say hello in French.", new Ai.GenerateOptions(null, 20, null, null))
    .data();
System.out.println("Output: " + res.content());
System.out.println("Output: " + res.usage().totalTokens() + " tokens");
```

```text
Output: Bonjour !
Output: 14 tokens
```

- `GenerateOptions(model, maxTokens, temperature, usageType)`: a `null` field is not sent. `generate(prompt)` sends the prompt only.
- `model` is optional: the org's default LLM is used. With no default the call fails with HTTP 400.
- Models from an outside provider answer 501 `K9O_NOT_IMPLEMENTED` until they are built.

## Handling errors

Every failure is thrown as `dev.k9o.KError`, an unchecked `RuntimeException`.

```java
try {
    k9o.from("tasks").select().execute(TaskItem.class);
} catch (KError e) {
    System.out.println("Output: code=" + e.getCode());
    System.out.println("Output: status=" + e.getStatus());
    System.out.println("Output: requestId=" + e.getRequestId());
    System.out.println("Output: hint=" + e.getRecommendation());
}
```

```text
Output: code=K9O_PROJECT_NOT_FOUND
Output: status=404
Output: requestId=req_123
Output: hint=Check the project slug.
```

Gateway errors keep the code the platform returned. See [error members](#error-members) for every accessor and SDK code.

## Appendix

- [Client options](#client-options)
- [Methods by namespace](#methods-by-namespace)
- [Error members](#error-members)
- [Retries](#retries)
- [Regional hosts](#regional-hosts)
- [Requirements](#requirements)
- [Related packages](#related-packages)
- [About Kilimanjaro](#about-kilimanjaro)

### Client options

Set these on `K9o.Opts.builder()`. See [authentication and project context](#authentication-and-project-context) for how to combine them.

| Option | Meaning |
|---|---|
| `apiKey` | Sent as `Authorization: Bearer <key>` on every request. |
| `orgSlug` | Your organization. On a platform host (`api.<apex>`) it builds `<orgSlug>.api.<apex>`. |
| `projectSlug` | Project for data, auth and storage calls. Required for those calls. |
| `baseUrl` | Host root, scheme and host only. Defaults to `https://api.k9o.io`. |
| `defaultHeader(name, value)` | Extra header sent on every request. |
| `httpClient` | Your own `java.net.http.HttpClient`. |
| `retryConfig` | A `RetryConfig`. See [retries](#retries). |

`orgSlug` only changes the URL when `baseUrl` is a platform host (`api.<apex>`). On any other host it is sent as a header only.

### Methods by namespace

Each namespace is a property or accessor on the client. Method names below are relative to the client, `k9o`.

#### Data

Query and change rows in a collection.

| Method | What it does |
|---|---|
| `from(c).select()` | Start a query; end it with execute / ExecuteAsync |
| `from(c).insert(record, Type.class)` | Insert one row |
| `from(c).update(patch)` | Update the row named by an id filter |
| `from(c).delete()` | Delete the row named by an id filter |
| `from(c).select().textSearch(column, query)` | Full-text search over a text column |
| `from(c).select().vectorSearch(column, embedding)` | Rows nearest to an embedding, closest first |
| `query(sql, params)` | Run one read-only SQL statement; `params` is a `List` or a `Map` |
| `search(c, input)` | Full-text search over a collection, best matches first |

#### Projects

List and manage projects in your organization.

| Method | What it does |
|---|---|
| `projects().list()` | List projects |
| `projects().create(input)` | Create a project. `Projects.CreateInput` needs `displayName`; `slug`, `region`, `custodyMode` are optional |
| `projects().get(slug)` | Read one project |
| `projects().update(slug, patch)` | Change a project's mutable fields with `Projects.UpdateInput` |

#### Gate

Sign-in and session calls for the end users of a project. Needs a project slug.

| Method | What it does |
|---|---|
| `gate().signUp(input)` | Register a user |
| `gate().signIn(input)` | Sign in with email and password |
| `gate().signOut(input)` | End a session. `Gate.SignOutInput` needs the refresh token |
| `gate().whoami()` | Return the current user |
| `gate().refreshToken(input)` | Exchange a refresh token |

#### Storage

Buckets and objects. Needs a project slug.

| Method | What it does |
|---|---|
| `storage().listBuckets()` | List buckets |
| `storage().getBucket(name)` | Read one bucket |
| `storage().createBucket(name)` | Create a bucket |
| `storage().updateBucket(name, patch)` | Change bucket settings |
| `storage().deleteBucket(name)` | Delete a bucket; pass empty = true to remove its objects too |
| `storage().from(bucket).upload(path, data)` | Upload text, bytes or a stream |
| `storage().from(bucket).createSignedUrl(path, ...)` | Create a temporary download link (1 to 604800 seconds) |
| `storage().from(bucket).list(prefix)` | List objects |
| `storage().from(bucket).remove(path)` | Delete an object |

#### Streams

Publish and read events on user streams. Needs a project slug.

| Method | What it does |
|---|---|
| `streams().create(displayName, maxBytes, retentionSeconds)` | Create a stream. `retentionSeconds` may be `null` for the default |
| `streams().list()` | List streams |
| `streams().get(slug)` | Read one stream |
| `streams().delete(slug)` | Retire a stream |
| `streams().publish(slug, data, subject)` | Append an event. `subject` is an optional routing suffix |
| `streams().replay(slug, fromSequence, untilSequence, limit)` | Read a range. The result has `events`, `nextCursor`, `hasMore` |
| `streams().subscribe(slug, fromSequence)` | Live events (`sequence`, `subject`, `data`, `publishedAt`); reconnects from the last sequence |

#### Approvals

Pending changes. Needs a project slug.

| Method | What it does |
|---|---|
| `approvals().list(status, limit)` | Newest pending changes. `limit` 1 to 200; not paginated. Both arguments may be `null` |
| `approvals().get(id)` | Read one change |
| `approvals().approve(id, reason)` | Approve. `reason` may be `null`. Check `status`: `pending_approval` means more approvers are needed |
| `approvals().reject(id, reason)` | Reject. `reason` is required |
| `approvals().bulk(action, criteria, reason)` | Approve or reject every change matching a predicate. Compare `affectedIds` with what you intended |

#### Approval policies

| Method | What it does |
|---|---|
| `approvalPolicies().list()` | All policies, oldest first |
| `approvalPolicies().create(input)` | Create from an `ApprovalPolicies.CreateInput` (`collection`, `operation`, `action`, `predicate`) |
| `approvalPolicies().delete(id)` | Delete a policy |

#### Schema

| Method | What it does |
|---|---|
| `schema().collections().list(limit, cursor)` | List collections by name, one page at a time. Both arguments may be `null` |

#### Realtime

Live events over server-sent events. There is no replay. After a `resync` frame, re-read current state and subscribe again.

| Method | What it does |
|---|---|
| `realtime().subscribe(topic, filter)` | Stream events (`topic`, `eventType`, `payload`, `occurredAt`); close the stream when done |
| `Realtime.Topic.collection(name)` | Topic for changes in a collection |
| `Realtime.Topic.workflowRuns()` | Topic for workflow run events |
| `Realtime.Topic.auditOrg(family)` | Topic for organization audit events |

#### Studio: workflows

Create and run workflows.

| Method | What it does |
|---|---|
| `studio().workflows().list(filters)` | List workflows. `Studio.WorkflowListFilters`: `status`, `limit`, `cursor` |
| `studio().workflows().create(input)` | Create a workflow |
| `studio().workflows().read(id)` | Read one workflow |
| `studio().workflows().update(id, updates)` | Change a workflow |
| `studio().workflows().delete(id)` | Delete a workflow |
| `studio().workflows().trigger(id, context)` | Start a run. Sends `{triggerContext}` |
| `studio().workflows().runs().list(id, limit, cursor)` | List runs of a workflow |
| `studio().workflows().runs().read(id, runId)` | Read one run |
| `studio().workflows().runs().cancel(id, runId)` | Cancel a run |

#### Studio: dashboards

Create and pin dashboards.

| Method | What it does |
|---|---|
| `studio().dashboards().list(status)` | List dashboards |
| `studio().dashboards().create(input)` | Create a dashboard |
| `studio().dashboards().read(id)` | Read one dashboard |
| `studio().dashboards().update(id, updates)` | Change a dashboard |
| `studio().dashboards().delete(id)` | Delete a dashboard |
| `studio().dashboards().pin(id)` | Pin a dashboard |
| `studio().dashboards().unpin(id)` | Unpin a dashboard |

#### Compute

Language models available to the project.

| Method | What it does |
|---|---|
| `compute().llms().list()` | List models |
| `compute().llms().read(modelId)` | Read one model |
| `compute().llms().invoke(modelId, input)` | Run a prompt. The result has `content` and `usage` |
| `compute().llms().invokeStream(modelId, input)` | Run a prompt. Chunks are OpenAI-style: `choices[0].delta.content` |

#### AI

Text embeddings.

| Method | What it does |
|---|---|
| `ai().embed(input)` | Embed one string or a list of strings |
| `ai().embed(model, input)` | Embed with a named model |
| `ai().generate(prompt, options)` | Run a prompt with the default or a named LLM. `options` is optional |

#### Functions

Deploy and call functions.

| Method | What it does |
|---|---|
| `functions().uploadArtifact(bytes, kind)` | Upload `"wasm"` or `"tar"` bytes. Returns the `artifactKey` |
| `functions().deploy(input)` | Deploy from an artifact: `name`, `runtime`, `tier`, `artifactKey`, `version` |
| `functions().list(status, limit, cursor)` | List functions. `list(status)` also works |
| `functions().get(name)` | Read one function |
| `functions().delete(name)` | Delete a function |
| `functions().invoke(name, payload, options)` | Call a function. `options` is optional; the payload is sent as `{payload}` |

#### Migrations

Change the project's schema.

| Method | What it does |
|---|---|
| `migrations().apply(list)` | Apply migrations now |
| `migrations().list()` | List applied migrations |
| `migrations().get(version)` | Read one applied migration |
| `migrations().queue(list)` | Queue migrations for approval |
| `migrations().listPending()` | List queued migrations |
| `migrations().applyPending(id)` | Apply a queued migration |
| `migrations().rejectPending(id, reason)` | Reject a queued migration |
| `migrations().deletePending(id)` | Remove a queued migration |

#### Policies

Policy bundles.

| Method | What it does |
|---|---|
| `policies().current()` | Read the active bundle |
| `policies().list(status)` | List bundles, filtered by `status` only |
| `policies().create(input)` | Create a bundle from a `Policies.PolicyInput` (`policy`, optional `rules`, `editorMode`) |
| `policies().validate(input)` | Check a policy document without saving |
| `policies().get(id)` | Read one bundle |
| `policies().update(id, input)` | Replace a bundle's policy document |
| `policies().delete(id)` | Delete a bundle |
| `policies().publish(id)` | Make a bundle active |

#### Audit

The project audit log.

| Method | What it does |
|---|---|
| `audit().query(opts)` | `Audit.QueryOpts`: `eventType`, `actorId`, `after`, `before`, `limit`, `cursor` |

#### Organization

Members, groups, roles, identity providers, SMTP and defaults.

| Method | What it does |
|---|---|
| `org().get()` | Read the organization |
| `org().members().list(status)` | List members |
| `org().members().invite(email, role)` | Invite a member |
| `org().members().remove(userId)` | Remove a member |
| `org().groups().list()` | List groups |
| `org().groups().get(groupId)` | Read one group |
| `org().gate().roles().list()` | List role grants |
| `org().gate().roles().grant(userId, role)` | Grant a role |
| `org().gate().roles().get(grantId)` | Read one role grant |
| `org().gate().roles().revoke(grantId)` | Revoke a role grant |
| `org().gate().idpProviders().list()` | List identity providers |
| `org().gate().idpProviders().create(input)` | Add an identity provider with `CreateIdpInput` |
| `org().gate().idpProviders().get(id)` | Read an identity provider |
| `org().gate().idpProviders().configure(id, patch)` | Change an identity provider |
| `org().gate().idpProviders().delete(id)` | Remove an identity provider |
| `org().smtp().set(input)` | Save SMTP settings (`passwordRef`, `fromAddress`) |
| `org().smtp().test(to, subject)` | Send a test email |
| `org().smtp().unset()` | Remove SMTP settings |
| `org().auditLog().query(type, limit)` | Search the organization audit log |
| `org().defaultLLM().get()` | Read the default model. Returns an object, not a string |
| `org().defaultLLM().set(modelId)` | Set the default model |
| `orgs().list()` | List organizations |
| `orgs().get(orgSlug)` | Read one organization |
| `orgs().current()` | Read the current organization |
| `orgs().restore(orgId)` | Restore a deleted organization |

#### Me

The signed-in operator's own account.

| Method | What it does |
|---|---|
| `me().profile().get()` | Read your profile |
| `me().profile().update(displayName)` | Change your display name |
| `me().sessions().list()` | List sessions |
| `me().sessions().revoke(sessionId)` | End one session |
| `me().sessions().revokeAll()` | End all sessions |
| `me().apiTokens().list()` | List API tokens |
| `me().apiTokens().create(label, scopeManifest, ttlSeconds)` | Create an API token |
| `me().apiTokens().delete(tokenId)` | Delete an API token |

#### Vault secrets

Store and reveal secrets. Revealing needs a one-time challenge.

| Method | What it does |
|---|---|
| `kibo().vault().secrets().list(opts)` | List secrets, optionally by prefix |
| `kibo().vault().secrets().get(name, region)` | Read one secret's metadata. To find a secret by exact name, use `list` with the name as `prefix` |
| `kibo().vault().secrets().create(name, region, input)` | Create a secret |
| `kibo().vault().secrets().revealChallenge(name, region)` | Get a one-time reveal challenge |
| `kibo().vault().secrets().reveal(name, region, challenge)` | Read the secret value |
| `kibo().vault().secrets().update(name, region, input)` | Change a secret |
| `kibo().vault().secrets().rotate(name, region)` | Rotate a secret |
| `kibo().vault().secrets().move(name, region, newPath)` | Move a secret to a new path |
| `kibo().vault().secrets().delete(name, region)` | Delete a secret |

### Error members

#### Members

| Accessor | Meaning |
|---|---|
| `getCode()` | Error code, for example `K9O_PROJECT_NOT_FOUND` |
| `getMessage()` | Human-readable message |
| `getStatus()` | HTTP status, or `null` for errors raised inside the SDK |
| `getRequestId()` | Trace id from the gateway, if any |
| `getRecommendation()` | Suggested next step, if any |
| `getContext()` | Read-only map with per-code details |

#### Codes raised by the SDK

| Code | When |
|---|---|
| `K9O_INVALID_BASE_URL` | `baseUrl` is malformed, not http(s), or has a path, query or fragment |
| `K9O_INVALID_FIELD_VALUE` | A slug, collection name or required argument is invalid |
| `K9O_MISSING_PROJECT_CONTEXT` | A project-scoped call was made without `projectSlug` |
| `K9O_UPSTREAM_UNAVAILABLE` | The request could not reach the platform |
| `K9O_INTERNAL_ERROR` | The response was not a valid envelope or error body |
| `K9O_INVALID_FILTER` | A realtime filter could not be encoded |

### Retries

Idempotent requests (GET, HEAD, OPTIONS, DELETE) are retried on transport errors and on 429, 502, 503 and 504 responses.

| Setting | Default |
|---|---|
| `maxRetries` | 3 |
| `backoff` | `"exponential"` (also `"linear"` or `"none"`) |
| `initialDelayMs` | 100 |
| `maxDelayMs` | 5000 |

Override with `retryConfig(new RetryConfig(maxRetries, backoff, initialDelayMs, maxDelayMs))`.

### Regional hosts

An organization host such as `https://acme.api.k9o.io` redirects to the organization's regional host.

- The SDK finds the regional host with one unauthenticated `HEAD` request.
- It sends all later requests to that host.
- The result is cached for the life of the client.

### Requirements

| Requirement | Version |
|---|---|
| JDK | 21 or newer |
| Jackson Databind | 2.18, installed transitively |
| HTTP client | The JDK's `java.net.http.HttpClient` |

### Related packages

| Package | Purpose |
|---|---|
| `dev.k9o:kibo` | Client-side encryption against your own KMS |

### About Kilimanjaro

Kilimanjaro is a modular developer platform for building secure enterprise applications.
You define AI agents, functions and data pipelines on the platform.
The SDKs let you deploy and invoke them, read and write your data, publish to and subscribe to streams, sign users in,
and encrypt with keys you hold.

It provides four building blocks:

- **Keep**: AI security and governance. Guardrails and policy for the agents you run.
- **Pulse**: data intelligence and real-time stream processing. Streams, analytics and live feeds.
- **Gate**: customer identity and access management. Sign-in and sessions, and automatic data scoping
  so a signed-in user reaches only their own data.
- **Kibo**: cryptographic keys and secrets management. Client-side encryption with keys you hold, and a secrets vault.

## License

Apache-2.0 © Formelo Inc. See `LICENSE` and `NOTICE`.

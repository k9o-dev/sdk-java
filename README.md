# dev.k9o:core

Java SDK for the Kilimanjaro (k9o) platform: a typed client for the data API, projects, auth, storage, realtime and more.

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Authentication and project context](#authentication-and-project-context)
- [Running queries](#running-queries)
- [Handling errors](#handling-errors)
- [Appendix](#appendix)

## Install

Maven:

```xml
<dependency>
    <groupId>dev.k9o</groupId>
    <artifactId>core</artifactId>
    <version>1.1.0</version>
</dependency>
```

Gradle:

```groovy
implementation 'dev.k9o:core:1.1.0'
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
            System.out.println(task.id() + " " + task.title());
        }
    }
}
```

```text
tsk_01 Write docs
tsk_02 Ship 1.0
```

## Authentication and project context

Every request carries your API key. The organization and project tell the platform where to run it.

- `apiKey` is sent as `Authorization: Bearer <key>`.
- `orgSlug` picks your organization's host on the default platform.
- `projectSlug` selects the project for data, auth and storage calls.
- The SDK does not read environment variables. Read the key yourself, as in the quick start.

The quick start uses the default platform. For a custom or self-hosted host, pass `baseUrl` and leave out `orgSlug`:

```java
K9o.K9oClient k9o = K9o.createClient(K9o.Opts.builder()
    .baseUrl("https://k9o.example.internal")
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
System.out.println("next cursor: " + next);
```

```text
next cursor: cur_abc
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

## Handling errors

Every failure is thrown as `dev.k9o.KError`, an unchecked `RuntimeException`.

```java
try {
    k9o.from("tasks").select().execute(TaskItem.class);
} catch (KError e) {
    System.out.println("code=" + e.getCode());
    System.out.println("status=" + e.getStatus());
    System.out.println("requestId=" + e.getRequestId());
    System.out.println("hint=" + e.getRecommendation());
}
```

```text
code=K9O_PROJECT_NOT_FOUND
status=404
requestId=req_123
hint=Check the project slug.
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

### Client options

Set these on `K9o.Opts.builder()`. See [authentication and project context](#authentication-and-project-context) for how to combine them.

| Option | Meaning |
|---|---|
| `apiKey` | Sent as `Authorization: Bearer <key>` on every request. |
| `orgSlug` | Your organization. On the default platform it selects `https://<orgSlug>.api.k9o.io`. |
| `projectSlug` | Project for data, auth and storage calls. Required for those calls. |
| `baseUrl` | Host root, scheme and host only. Defaults to `https://api.k9o.io`. |
| `defaultHeader(name, value)` | Extra header sent on every request. |
| `httpClient` | Your own `java.net.http.HttpClient`. |
| `retryConfig` | A `RetryConfig`. See [retries](#retries). |

`orgSlug` only changes the URL when `baseUrl` is the platform apex. On any other host it is not applied to the URL.

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

#### Projects

List and manage projects in your organization.

| Method | What it does |
|---|---|
| `projects().list()` | List projects |
| `projects().create(input)` | Create a project |
| `projects().get(slug)` | Read one project |
| `projects().update(slug, patch)` | Change a project's mutable fields |

#### Gate

Sign-in and session calls for the end users of a project. Needs a project slug.

| Method | What it does |
|---|---|
| `gate().signUp(input)` | Register a user |
| `gate().signIn(input)` | Sign in with email and password |
| `gate().signOut(input)` | End a session |
| `gate().whoami()` | Return the current user |
| `gate().refreshToken(input)` | Exchange a refresh token |
| `gate().verifySession()` | Check that the session is valid |
| `gate().hosted()` | Get the hosted sign-in URL and WebAuthn rpId (no credential sent) |

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

#### Realtime

Live events over server-sent events, with a polling fallback.

| Method | What it does |
|---|---|
| `realtime().subscribe(topic, filter)` | Stream events; close the stream when done |
| `realtime().replay(input)` | Fetch past events from a sequence number |
| `realtime().subscribePoll(topic, fromSequence, filter)` | Poll for events instead of streaming |
| `Realtime.Topic.collection(name)` | Topic for changes in a collection |
| `Realtime.Topic.workflowRuns()` | Topic for workflow run events |
| `Realtime.Topic.auditOrg(family)` | Topic for organization audit events |

#### Studio: workflows

Create and run workflows.

| Method | What it does |
|---|---|
| `studio().workflows().list(filters)` | List workflows |
| `studio().workflows().create(input)` | Create a workflow |
| `studio().workflows().read(id)` | Read one workflow |
| `studio().workflows().update(id, updates)` | Change a workflow |
| `studio().workflows().delete(id)` | Delete a workflow |
| `studio().workflows().trigger(id, context)` | Start a run |
| `studio().workflows().runs().list(id, status)` | List runs of a workflow |
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
| `compute().llms().list(modality)` | List models |
| `compute().llms().read(modelId)` | Read one model |
| `compute().llms().invoke(modelId, input)` | Run a prompt and get the full answer |
| `compute().llms().invokeStream(modelId, input)` | Run a prompt and stream chunks |

#### AI

Text embeddings.

| Method | What it does |
|---|---|
| `ai().embed(input)` | Embed one string or a list of strings |
| `ai().embed(model, input)` | Embed with a named model |

#### Functions

Deploy and call functions.

| Method | What it does |
|---|---|
| `functions().deploy(input)` | Deploy a function |
| `functions().list(status)` | List functions |
| `functions().get(name)` | Read one function |
| `functions().delete(name)` | Delete a function |
| `functions().invoke(name, payload)` | Call a function |

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
| `policies().list(status)` | List bundles |
| `policies().create(name, rules)` | Create a bundle |
| `policies().validate(rules)` | Check rules without saving |
| `policies().get(id)` | Read one bundle |
| `policies().update(id, patch)` | Change a bundle |
| `policies().delete(id)` | Delete a bundle |
| `policies().publish(id)` | Make a bundle active |

#### Audit

The project audit log.

| Method | What it does |
|---|---|
| `audit().query(opts)` | Search audit events by time, type, actor or target |

#### Organization

Members, groups, roles, identity providers, SMTP and defaults.

| Method | What it does |
|---|---|
| `org().get()` | Read the organization |
| `org().members().list(status)` | List members |
| `org().members().invite(email, role)` | Invite a member |
| `org().members().get(userId)` | Read one member |
| `org().members().remove(userId)` | Remove a member |
| `org().groups().list()` | List groups |
| `org().groups().create(name)` | Create a group |
| `org().groups().get(groupId)` | Read one group |
| `org().groups().delete(groupId)` | Delete a group |
| `org().gate().roles().list(userId, role)` | List role grants |
| `org().gate().roles().grant(userId, role)` | Grant a role |
| `org().gate().roles().revoke(grantId)` | Revoke a role grant |
| `org().gate().idpProviders().list()` | List identity providers |
| `org().gate().idpProviders().create(name, kind, config)` | Add an identity provider |
| `org().gate().idpProviders().configure(id, patch)` | Change an identity provider |
| `org().gate().idpProviders().delete(id)` | Remove an identity provider |
| `org().smtp().get()` | Read SMTP settings |
| `org().smtp().set(input)` | Save SMTP settings |
| `org().smtp().test(to, fromOverride)` | Send a test email |
| `org().smtp().unset()` | Remove SMTP settings |
| `org().auditLog().query(opts)` | Search the organization audit log |
| `org().defaultLLM().get()` | Read the default model |
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
| `me().apiTokens().create(name, expiresInDays, scope)` | Create an API token |
| `me().apiTokens().delete(tokenId)` | Delete an API token |
| `me().notifications().list(unreadOnly, limit)` | List notifications |
| `me().notifications().markRead(id)` | Mark one as read |
| `me().notifications().markAllRead()` | Mark all as read |
| `me().auditLog().query(opts)` | Search your own audit events |
| `me().dataExport().request(format)` | Request a data export |
| `me().dataExport().status(jobId)` | Check an export job |

#### Vault secrets

Store and reveal secrets. Revealing needs a one-time challenge.

| Method | What it does |
|---|---|
| `kibo().vault().secrets().list(opts)` | List secrets, optionally by prefix |
| `kibo().vault().secrets().get(name, region)` | Read secret metadata |
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

## License

Apache-2.0 © Formelo Inc. See `LICENSE` and `NOTICE`.

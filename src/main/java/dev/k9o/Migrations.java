package dev.k9o;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Migration lifecycle namespace — {@code client.migrations()}.
 * Java idiom: camelCase + Envelope return + throws KError.
 *
 * <p>Migration shape — STRUCTURED
 * DECLARATIVE DDL, never raw SQL. Every schema change is a typed, key-tagged
 * {@link Operation}, so schema drift is impossible at the SDK layer. The
 * migration Operation union is CONTROL-PLANE and so is statically TYPED for
 * this tier-1 static SDK (closed-union is enforced at wire + server-validation
 * for all langs; compile-time typing is per-tier).
 *
 * <p>The landed static-tier reference is {@code packages/go/migrations.go};
 * the canonical wire shape is {@code packages/core/src/migrations.ts}.
 * Op set is FROZEN at 6 (no alter_column / rename_table / constraint at v2);
 * {@code rename_column} is the pending 7th and is NOT modelled here.
 */
public final class Migrations {

    private final HttpTransport.Config config;

    Migrations(HttpTransport.Config config) {
        this.config = config;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "migrations operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    // ----------------------------------------------------------------------
    // Structured DDL model (control-plane; statically typed for this tier).
    // ----------------------------------------------------------------------

    /**
     * Foreign-key reference on a column — compiles to a REFERENCES clause.
     * {@code onDelete} / {@code onUpdate} accept
     * "CASCADE" | "RESTRICT" | "SET NULL" | "NO ACTION".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ColumnReferences(
        @JsonProperty("collection") String collection,
        @JsonProperty("on") String on,
        @JsonProperty("on_delete") String onDelete,
        @JsonProperty("on_update") String onUpdate
    ) {
        public ColumnReferences(String collection, String on) {
            this(collection, on, null, null);
        }
    }

    /**
     * At-rest field encryption, modelling the wire union
     * {@code true | { store, searchable? }}. {@link #inline()} renders the bare
     * {@code true} shorthand; the object form opts into a {@code store}
     * ("inline" | "vault") and the D-mode {@code searchable} index. NOTE:
     * {@code searchable} is NESTED inside this object — it is NOT a sibling of
     * the column. Serialized by {@link EncryptedSerializer}
     * to the polymorphic JSON form.
     */
    @JsonSerialize(using = EncryptedSerializer.class)
    @JsonDeserialize(using = EncryptedDeserializer.class)
    public static final class Encrypted {
        /** null in the bare-{@code true} shorthand; "inline" | "vault" otherwise. */
        public final String store;
        /** D-mode searchable-encryption index (object form only). */
        public final Boolean searchable;

        private Encrypted(String store, Boolean searchable) {
            this.store = store;
            this.searchable = searchable;
        }

        /** The {@code true} shorthand: inline-stored encrypted column. */
        public static Encrypted inline() {
            return new Encrypted(null, null);
        }

        /** The object form: {@code { store, searchable? }} (store required). */
        public static Encrypted of(String store, Boolean searchable) {
            return new Encrypted(store, searchable);
        }

        /** True when this is the bare {@code true} shorthand. */
        public boolean isShorthand() {
            return store == null;
        }
    }

    /** Jackson serializer for the {@code true | { store, searchable? }} union. */
    static final class EncryptedSerializer extends StdSerializer<Encrypted> {
        EncryptedSerializer() { super(Encrypted.class); }

        @Override
        public void serialize(Encrypted v, JsonGenerator gen, SerializerProvider provider) throws IOException {
            if (v.isShorthand()) {
                gen.writeBoolean(true);
                return;
            }
            gen.writeStartObject();
            gen.writeStringField("store", v.store);
            if (v.searchable != null) {
                gen.writeBooleanField("searchable", v.searchable);
            }
            gen.writeEndObject();
        }
    }

    /** Jackson deserializer for the {@code true | { store, searchable? }} union. */
    static final class EncryptedDeserializer extends StdDeserializer<Encrypted> {
        EncryptedDeserializer() { super(Encrypted.class); }

        @Override
        public Encrypted deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            if (node.isBoolean() || node.isTextual() || node.isNumber()) {
                return Encrypted.inline();
            }
            String store = node.hasNonNull("store") ? node.get("store").asText() : null;
            Boolean searchable = node.hasNonNull("searchable") ? node.get("searchable").asBoolean() : null;
            return Encrypted.of(store, searchable);
        }
    }

    /**
     * A column definition. {@code type} is the column-type
     * vocabulary (parser-owned; kept as a {@code String} rather than a
     * fabricated enum). The protection attributes ({@code encrypted},
     * {@code blindIndexed}) declare at-rest field encryption.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ColumnSpec(
        @JsonProperty("name") String name,
        @JsonProperty("type") String type,
        @JsonProperty("primary_key") Boolean primaryKey,
        @JsonProperty("not_null") Boolean notNull,
        @JsonProperty("unique") Boolean unique,
        @JsonProperty("default") String defaultValue,
        @JsonProperty("references") ColumnReferences references,
        @JsonProperty("encrypted") Encrypted encrypted,
        @JsonProperty("blind_indexed") Boolean blindIndexed
    ) {
        /** Minimal column: just name + type. */
        public ColumnSpec(String name, String type) {
            this(name, type, null, null, null, null, null, null, null);
        }
    }

    /** An index definition. {@code name} defaults to {@code <table>_<cols>_idx}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndexSpec(
        @JsonProperty("name") String name,
        @JsonProperty("columns") List<String> columns,
        @JsonProperty("unique") Boolean unique
    ) {
        public IndexSpec(List<String> columns) {
            this(null, columns, null);
        }
    }

    /** Payload of a {@code create_table} operation. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateTableOp(
        @JsonProperty("name") String name,
        @JsonProperty("columns") List<ColumnSpec> columns,
        @JsonProperty("indexes") List<IndexSpec> indexes
    ) {
        public CreateTableOp(String name, List<ColumnSpec> columns) {
            this(name, columns, null);
        }
    }

    /** Payload of an {@code add_column} operation. */
    public record AddColumnOp(
        @JsonProperty("table") String table,
        @JsonProperty("column") ColumnSpec column
    ) {}

    /** Payload of a {@code drop_column} operation. */
    public record DropColumnOp(
        @JsonProperty("table") String table,
        @JsonProperty("column") String column
    ) {}

    /** Payload of a {@code create_index} operation. */
    public record CreateIndexOp(
        @JsonProperty("table") String table,
        @JsonProperty("index") IndexSpec index
    ) {}

    /** Payload of a {@code drop_index} operation. */
    public record DropIndexOp(
        @JsonProperty("name") String name
    ) {}

    /**
     * Payload of an {@code alter_table} op — the BATCHED column mutation: one op
     * carries optional {@code add_columns} + {@code drop_columns} (not separate
     * add/drop ops). {@code add_columns} entries are full {@link ColumnSpec}
     * (incl encrypted/blind_indexed); {@code drop_columns} is column names.
     */
    public record AlterTableOp(
        @JsonProperty("name") String name,
        @JsonProperty("add_columns") List<ColumnSpec> addColumns,
        @JsonProperty("drop_columns") List<String> dropColumns
    ) {}

    /**
     * A single declarative DDL operation — KEY-TAGGED (exactly one field set),
     * the shape the gateway applies. The v2 set is these 7 — no alter_column,
     * rename_table, or constraint ops at v2. {@code alter_table} is the BATCHED
     * column mutation (live in the gateway runner).
     * {@code rename_column} remains a pending future op and is not modelled. Java
     * has no native sum type, so — mirroring the Go reference — the union is a
     * record of nullable fields ({@code @JsonInclude(NON_NULL)}); construct via
     * the static factories, which set exactly one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Operation(
        @JsonProperty("create_table") CreateTableOp createTable,
        @JsonProperty("drop_table") String dropTable,
        @JsonProperty("add_column") AddColumnOp addColumn,
        @JsonProperty("drop_column") DropColumnOp dropColumn,
        @JsonProperty("alter_table") AlterTableOp alterTable,
        @JsonProperty("create_index") CreateIndexOp createIndex,
        @JsonProperty("drop_index") DropIndexOp dropIndex
    ) {
        public static Operation createTable(CreateTableOp op) {
            return new Operation(op, null, null, null, null, null, null);
        }
        public static Operation dropTable(String table) {
            return new Operation(null, table, null, null, null, null, null);
        }
        public static Operation addColumn(AddColumnOp op) {
            return new Operation(null, null, op, null, null, null, null);
        }
        public static Operation dropColumn(DropColumnOp op) {
            return new Operation(null, null, null, op, null, null, null);
        }
        public static Operation alterTable(AlterTableOp op) {
            return new Operation(null, null, null, null, op, null, null);
        }
        public static Operation createIndex(CreateIndexOp op) {
            return new Operation(null, null, null, null, null, op, null);
        }
        public static Operation dropIndex(DropIndexOp op) {
            return new Operation(null, null, null, null, null, null, op);
        }
    }

    /**
     * A declarative authorization/relationship rule (compiles to RLS USING +
     * WITH CHECK). The rule grammar is the policy-expression DSL;
     * {@code rule} is left as {@code Object} to mirror the spec's open shape.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ActionRule(
        @JsonProperty("rule") Object rule
    ) {}

    /** A declared relationship for embedding/joins (references). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RelationshipSpec(
        @JsonProperty("collection") String collection,
        @JsonProperty("on") String on,
        @JsonProperty("as") String as,
        @JsonProperty("type") String type
    ) {}

    /** The per-action RLS rules on a collection. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollectionActions(
        @JsonProperty("select") ActionRule select,
        @JsonProperty("insert") ActionRule insert,
        @JsonProperty("update") ActionRule update,
        @JsonProperty("delete") ActionRule delete
    ) {}

    /** A per-collection policy block (RLS + relationships),. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollectionPolicy(
        @JsonProperty("references") List<RelationshipSpec> references,
        @JsonProperty("actions") CollectionActions actions
    ) {}

    /** The per-action rules on a storage bucket. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StorageBucketActions(
        @JsonProperty("read") ActionRule read,
        @JsonProperty("upload") ActionRule upload,
        @JsonProperty("delete") ActionRule delete,
        @JsonProperty("list") ActionRule list
    ) {}

    /** A per-bucket storage policy block,. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StorageBucketPolicy(
        @JsonProperty("public") Boolean isPublic,
        @JsonProperty("actions") StorageBucketActions actions
    ) {}

    /** The storage section of a {@link MigrationPolicy}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MigrationStorage(
        @JsonProperty("buckets") Map<String, StorageBucketPolicy> buckets
    ) {}

    /** The optional policy section authored alongside a migration. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MigrationPolicy(
        @JsonProperty("collections") Map<String, CollectionPolicy> collections,
        @JsonProperty("storage") MigrationStorage storage
    ) {}

    /**
     * A migration — a versioned, checksummed unit of structured DDL.
     * {@code up} applies the change; {@code down} reverses it. Both
     * are {@link Operation} arrays.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Migration(
        @JsonProperty("version") int version,
        @JsonProperty("slug") String slug,
        @JsonProperty("up") List<Operation> up,
        @JsonProperty("down") List<Operation> down,
        @JsonProperty("policy") MigrationPolicy policy
    ) {
        public Migration(int version, String slug, List<Operation> up, List<Operation> down) {
            this(version, slug, up, down, null);
        }
    }

    /** Applied-migration record returned by {@link #list()} + {@link #get(String)}. */
    public record AppliedMigration(
        @JsonProperty("version") int version,
        @JsonProperty("slug") String slug,
        @JsonProperty("checksum") String checksum,
        @JsonProperty("appliedAt") String appliedAt
    ) {}

    /** Pending-migration record returned by {@link #listPending()}. */
    public record PendingMigration(
        @JsonProperty("pendingId") String pendingId,
        @JsonProperty("version") int version,
        @JsonProperty("slug") String slug,
        @JsonProperty("checksum") String checksum,
        @JsonProperty("submittedAt") String submittedAt,
        @JsonProperty("submittedBy") String submittedBy
    ) {}

    // ----------------------------------------------------------------------
    // Namespace methods (surface + routes UNCHANGED).
    // ----------------------------------------------------------------------

    /** Apply migrations immediately (PROJECT_ADMIN minimum). */
    public Envelope<Map<String, Object>> apply(List<Migration> migrations) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "migrations/apply"),
            Map.of("migrations", migrations), null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }

    /** List applied migrations on the project. */
    public Envelope<List<AppliedMigration>> list() {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "migrations"),
            null, null, new TypeReference<Envelope<List<AppliedMigration>>>() {});
    }

    /** Get a single applied migration by version. */
    public Envelope<AppliedMigration> get(String version) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "migrations/" + enc(version)),
            null, null, new TypeReference<Envelope<AppliedMigration>>() {});
    }

    /** Queue migrations for maker-checker approval. */
    public Envelope<Map<String, Object>> queue(List<Migration> migrations) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "migrations/queue"),
            Map.of("migrations", migrations), null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }

    /** List pending (queued, not yet approved) migrations. */
    public Envelope<List<PendingMigration>> listPending() {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "migrations/pending"),
            null, null, new TypeReference<Envelope<List<PendingMigration>>>() {});
    }

    /** Approve + apply a pending migration. */
    public Envelope<AppliedMigration> applyPending(String pendingId) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(slug, "migrations/pending/" + enc(pendingId) + "/apply"),
            null, null, new TypeReference<Envelope<AppliedMigration>>() {});
    }

    /** Reject a pending migration (does not apply). */
    public Envelope<Map<String, Object>> rejectPending(String pendingId, String reason) {
        String slug = requireProjectSlug();
        Object body = reason != null ? Map.of("reason", reason) : null;
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(slug, "migrations/pending/" + enc(pendingId) + "/reject"),
            body, null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }

    /** Delete a pending migration from the queue. */
    public Envelope<Map<String, Object>> deletePending(String pendingId) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "DELETE",
            UrlGrammar.projectPath(slug, "migrations/pending/" + enc(pendingId)),
            null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }
}

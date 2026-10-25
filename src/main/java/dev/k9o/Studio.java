package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Studio namespace — {@code client.studio()} (workflows + dashboards).
 * Java idiom: camelCase
 * methods + {@link Envelope} return + throws {@link KError}.
 *
 * <p>6 K9O_WORKFLOW_* error codes + 2 K9O_DASHBOARD_*
 * codes flow through the existing KError wire mapping;
 * narrow on {@code e.getCode()}.
 */
public final class Studio {

    private final HttpTransport.Config config;
    private final Workflows workflows;
    private final Dashboards dashboards;

    Studio(HttpTransport.Config config) {
        this.config = config;
        this.workflows = new Workflows(config);
        this.dashboards = new Dashboards(config);
    }

    public Workflows workflows() { return workflows; }
    public Dashboards dashboards() { return dashboards; }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String requireProjectSlug(HttpTransport.Config config, String surface) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT",
                surface + " operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    // ------------------------------------------------------------------
    // Shapes — workflows
    // ------------------------------------------------------------------

    /** Workflow record. */
    public record Workflow(
        @JsonProperty("id") String id,
        @JsonProperty("projectId") String projectId,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("status") String status,
        @JsonProperty("triggerType") String triggerType,
        @JsonProperty("triggerConfig") Map<String, Object> triggerConfig,
        @JsonProperty("actions") List<Map<String, Object>> actions,
        @JsonProperty("rbacBinding") Map<String, Object> rbacBinding,
        @JsonProperty("authoredBy") String authoredBy,
        @JsonProperty("authoredAt") String authoredAt,
        @JsonProperty("updatedBy") String updatedBy,
        @JsonProperty("updatedAt") String updatedAt
    ) {}

    /** Workflow run record. */
    public record WorkflowRun(
        @JsonProperty("id") String id,
        @JsonProperty("workflowId") String workflowId,
        @JsonProperty("projectId") String projectId,
        @JsonProperty("status") String status,
        @JsonProperty("triggerContext") Map<String, Object> triggerContext,
        @JsonProperty("perStepStatus") List<Map<String, Object>> perStepStatus,
        @JsonProperty("costAccountingTotal") Double costAccountingTotal,
        @JsonProperty("errorContext") Map<String, Object> errorContext,
        @JsonProperty("startedAt") String startedAt,
        @JsonProperty("completedAt") String completedAt,
        @JsonProperty("firedBy") String firedBy
    ) {}

    /** Create input for {@link Workflows#create}. */
    public record CreateWorkflowInput(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("status") String status,
        @JsonProperty("triggerType") String triggerType,
        @JsonProperty("triggerConfig") Map<String, Object> triggerConfig,
        @JsonProperty("actions") List<Map<String, Object>> actions,
        @JsonProperty("rbacBinding") Map<String, Object> rbacBinding
    ) {}

    /** Optional filters for {@link Workflows#list}. */
    public record WorkflowListFilters(String status, String triggerType, Integer limit, String cursor) {
        Map<String, String> toQuery() {
            Map<String, String> q = new HashMap<>();
            if (status != null) q.put("status", status);
            if (triggerType != null) q.put("triggerType", triggerType);
            if (limit != null) q.put("limit", String.valueOf(limit));
            if (cursor != null) q.put("cursor", cursor);
            return q;
        }
    }

    public static final class Workflows {
        private final HttpTransport.Config config;
        private final Runs runs;

        Workflows(HttpTransport.Config config) {
            this.config = config;
            this.runs = new Runs(config);
        }

        public Runs runs() { return runs; }

        public Envelope<List<Workflow>> list(WorkflowListFilters filters) {
            String slug = requireProjectSlug(config, "studio.workflows");
            return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "workflows"),
                null, filters == null ? null : filters.toQuery(),
                new TypeReference<Envelope<List<Workflow>>>() {});
        }

        public Envelope<Workflow> create(CreateWorkflowInput input) {
            String slug = requireProjectSlug(config, "studio.workflows");
            return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "workflows"),
                input, null, new TypeReference<Envelope<Workflow>>() {});
        }

        public Envelope<Workflow> read(String workflowId) {
            String slug = requireProjectSlug(config, "studio.workflows");
            return HttpTransport.request(config, "GET",
                UrlGrammar.projectPath(slug, "workflows/" + enc(workflowId)),
                null, null, new TypeReference<Envelope<Workflow>>() {});
        }

        public Envelope<Workflow> update(String workflowId, Map<String, Object> updates) {
            String slug = requireProjectSlug(config, "studio.workflows");
            return HttpTransport.request(config, "PATCH",
                UrlGrammar.projectPath(slug, "workflows/" + enc(workflowId)),
                updates, null, new TypeReference<Envelope<Workflow>>() {});
        }

        public Envelope<Map<String, Object>> delete(String workflowId) {
            String slug = requireProjectSlug(config, "studio.workflows");
            return HttpTransport.request(config, "DELETE",
                UrlGrammar.projectPath(slug, "workflows/" + enc(workflowId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        /** Manual-trigger a run (trigger_type=manual only). */
        public Envelope<WorkflowRun> trigger(String workflowId, Map<String, Object> triggerContext) {
            String slug = requireProjectSlug(config, "studio.workflows");
            Object body = triggerContext == null ? Map.of() : triggerContext;
            return HttpTransport.request(config, "POST",
                UrlGrammar.projectPath(slug, "workflows/" + enc(workflowId) + "/runs"),
                body, null, new TypeReference<Envelope<WorkflowRun>>() {});
        }

        public static final class Runs {
            private final HttpTransport.Config config;

            Runs(HttpTransport.Config config) { this.config = config; }

            public Envelope<List<WorkflowRun>> list(String workflowId, String status) {
                String slug = requireProjectSlug(config, "studio.workflows");
                Map<String, String> q = status == null ? null : Map.of("status", status);
                return HttpTransport.request(config, "GET",
                    UrlGrammar.projectPath(slug, "workflows/" + enc(workflowId) + "/runs"),
                    null, q, new TypeReference<Envelope<List<WorkflowRun>>>() {});
            }

            public Envelope<WorkflowRun> read(String workflowId, String runId) {
                String slug = requireProjectSlug(config, "studio.workflows");
                return HttpTransport.request(config, "GET",
                    UrlGrammar.projectPath(slug, "workflows/" + enc(workflowId) + "/runs/" + enc(runId)),
                    null, null, new TypeReference<Envelope<WorkflowRun>>() {});
            }

            public Envelope<WorkflowRun> cancel(String workflowId, String runId) {
                String slug = requireProjectSlug(config, "studio.workflows");
                return HttpTransport.request(config, "POST",
                    UrlGrammar.projectPath(slug, "workflows/" + enc(workflowId) + "/runs/" + enc(runId) + "/cancel"),
                    null, null, new TypeReference<Envelope<WorkflowRun>>() {});
            }
        }
    }

    // ------------------------------------------------------------------
    // Shapes — dashboards
    // ------------------------------------------------------------------

    /** Dashboard record. */
    public record Dashboard(
        @JsonProperty("id") String id,
        @JsonProperty("projectId") String projectId,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("status") String status,
        @JsonProperty("layoutGrid") Map<String, Object> layoutGrid,
        @JsonProperty("widgets") List<Map<String, Object>> widgets,
        @JsonProperty("filterConfig") Map<String, Object> filterConfig,
        @JsonProperty("pinnedToWorkspace") Boolean pinnedToWorkspace,
        @JsonProperty("rbacBinding") Map<String, Object> rbacBinding,
        @JsonProperty("authoredBy") String authoredBy,
        @JsonProperty("authoredAt") String authoredAt,
        @JsonProperty("updatedBy") String updatedBy,
        @JsonProperty("updatedAt") String updatedAt
    ) {}

    public record CreateDashboardInput(
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("status") String status,
        @JsonProperty("layoutGrid") Map<String, Object> layoutGrid,
        @JsonProperty("widgets") List<Map<String, Object>> widgets,
        @JsonProperty("filterConfig") Map<String, Object> filterConfig
    ) {}

    public static final class Dashboards {
        private final HttpTransport.Config config;

        Dashboards(HttpTransport.Config config) { this.config = config; }

        public Envelope<List<Dashboard>> list(String status) {
            String slug = requireProjectSlug(config, "studio.dashboards");
            Map<String, String> q = status == null ? null : Map.of("status", status);
            return HttpTransport.request(config, "GET",
                UrlGrammar.projectPath(slug, "dashboards"), null, q,
                new TypeReference<Envelope<List<Dashboard>>>() {});
        }

        public Envelope<Dashboard> create(CreateDashboardInput input) {
            String slug = requireProjectSlug(config, "studio.dashboards");
            return HttpTransport.request(config, "POST",
                UrlGrammar.projectPath(slug, "dashboards"), input, null,
                new TypeReference<Envelope<Dashboard>>() {});
        }

        public Envelope<Dashboard> read(String dashboardId) {
            String slug = requireProjectSlug(config, "studio.dashboards");
            return HttpTransport.request(config, "GET",
                UrlGrammar.projectPath(slug, "dashboards/" + enc(dashboardId)),
                null, null, new TypeReference<Envelope<Dashboard>>() {});
        }

        public Envelope<Dashboard> update(String dashboardId, Map<String, Object> updates) {
            String slug = requireProjectSlug(config, "studio.dashboards");
            return HttpTransport.request(config, "PATCH",
                UrlGrammar.projectPath(slug, "dashboards/" + enc(dashboardId)),
                updates, null, new TypeReference<Envelope<Dashboard>>() {});
        }

        public Envelope<Map<String, Object>> delete(String dashboardId) {
            String slug = requireProjectSlug(config, "studio.dashboards");
            return HttpTransport.request(config, "DELETE",
                UrlGrammar.projectPath(slug, "dashboards/" + enc(dashboardId)),
                null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
        }

        public Envelope<Dashboard> pin(String dashboardId) {
            String slug = requireProjectSlug(config, "studio.dashboards");
            return HttpTransport.request(config, "POST",
                UrlGrammar.projectPath(slug, "dashboards/" + enc(dashboardId) + "/pin"),
                null, null, new TypeReference<Envelope<Dashboard>>() {});
        }

        public Envelope<Dashboard> unpin(String dashboardId) {
            String slug = requireProjectSlug(config, "studio.dashboards");
            return HttpTransport.request(config, "POST",
                UrlGrammar.projectPath(slug, "dashboards/" + enc(dashboardId) + "/unpin"),
                null, null, new TypeReference<Envelope<Dashboard>>() {});
        }
    }
}

package dev.k9o;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Client-side validation of approvals, approval policies and schema collections: K9O_INVALID_FIELD_VALUE before any request. */
class ApprovalsValidationTest {

    static final Map<String, Object> PRED = Map.of("gt", List.of("change.row.amount", 1000));

    /** A client whose transport must never be reached: any request would fail with a different code. */
    K9o.K9oClient client() {
        return K9o.createClient(K9o.Opts.builder().baseUrl("http://127.0.0.1:1").projectSlug("portal").build());
    }

    void invalid(Supplier<?> call) {
        KError e = assertThrows(KError.class, call::get);
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode(), e.getMessage());
    }

    @Test
    void listLimitRange() {
        invalid(() -> client().approvals().list(null, 0));
        invalid(() -> client().approvals().list(null, 201));
        invalid(() -> client().approvals().list("approved", -1));
        invalid(() -> client().schema().collections().list(0, null));
        invalid(() -> client().schema().collections().list(201, null));
    }

    @Test
    void idsAndReasons() {
        invalid(() -> client().approvals().get(""));
        invalid(() -> client().approvals().get(null));
        invalid(() -> client().approvals().approve(""));
        invalid(() -> client().approvals().reject("apr_1", null));
        invalid(() -> client().approvals().reject("apr_1", ""));
        invalid(() -> client().approvals().reject("apr_1", "   "));
        invalid(() -> client().approvals().reject("", "no"));
        invalid(() -> client().approvalPolicies().delete(""));
    }

    @Test
    void bulk() {
        invalid(() -> client().approvals().bulk("delete", PRED, null));
        invalid(() -> client().approvals().bulk(null, PRED, null));
        invalid(() -> client().approvals().bulk("approve", null, null));
        invalid(() -> client().approvals().bulk("reject", PRED, null));
        invalid(() -> client().approvals().bulk("reject", PRED, " "));
    }

    @Test
    void policyCreate() {
        ApprovalPolicies p = client().approvalPolicies();
        invalid(() -> p.create(null));
        invalid(() -> p.create(new ApprovalPolicies.CreateInput(null, "insert", "queue", PRED)));
        invalid(() -> p.create(new ApprovalPolicies.CreateInput("1orders", "insert", "queue", PRED)));
        invalid(() -> p.create(new ApprovalPolicies.CreateInput("or-ders", "insert", "queue", PRED)));
        invalid(() -> p.create(new ApprovalPolicies.CreateInput("a".repeat(64), "insert", "queue", PRED)));
        invalid(() -> p.create(new ApprovalPolicies.CreateInput("orders", "upsert", "queue", PRED)));
        invalid(() -> p.create(new ApprovalPolicies.CreateInput("orders", null, "queue", PRED)));
        invalid(() -> p.create(new ApprovalPolicies.CreateInput("orders", "insert", "block", PRED)));
        invalid(() -> p.create(new ApprovalPolicies.CreateInput("orders", "insert", null, PRED)));
        invalid(() -> p.create(new ApprovalPolicies.CreateInput("orders", "insert", "queue", null)));
    }

    @Test
    void validInputsAreNotRejectedLocally() {
        AtomicInteger n = new AtomicInteger();
        // Boundary values pass validation; the unreachable host then fails with a transport code, not a field error.
        for (Supplier<?> call : List.<Supplier<?>>of(
            () -> client().approvals().list(null, 1),
            () -> client().approvals().list(null, 200),
            () -> client().approvalPolicies().create(new ApprovalPolicies.CreateInput("a".repeat(63), "delete", "auto_approve", PRED)),
            () -> client().approvalPolicies().create(new ApprovalPolicies.CreateInput("_x", "insert", "queue", Map.of())))) {
            try {
                call.get();
            } catch (KError e) {
                assertNotEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
                n.incrementAndGet();
            }
        }
        assertEquals(4, n.get());
    }

    @Test
    void projectSlugRequired() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder().baseUrl("http://127.0.0.1:1").build());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> c.approvals().list()).getCode());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> c.approvalPolicies().list()).getCode());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> c.schema().collections().list()).getCode());
    }
}

package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Gate (Identity-Aware Kernel) namespace — {@code client.gate().*}.
 * Project-scoped — requires {@code projectSlug} bound on
 * the client; throws {@link KError K9O_MISSING_PROJECT_CONTEXT} otherwise.
 */
public class Gate {

    private final HttpTransport.Config config;

    Gate(HttpTransport.Config config) {
        this.config = config;
    }

    public record SignInInput(
        @JsonProperty("email") String email,
        @JsonProperty("password") String password
    ) {}

    public record SignUpInput(
        @JsonProperty("email") String email,
        @JsonProperty("password") String password,
        @JsonProperty("displayName") String displayName
    ) {}

    public record SignOutInput(
        @JsonProperty("refreshToken") String refreshToken
    ) {}

    public record RefreshTokenInput(
        @JsonProperty("refreshToken") String refreshToken
    ) {}

    /** {@code GET /@<slug>/gate/hosted} payload: the hosted sign-in page URL and the WebAuthn relying-party id. */
    public record HostedGateInfo(
        @JsonProperty("signInUrl") String signInUrl,
        @JsonProperty("rpId") String rpId
    ) {}

    private String requireProject() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError(
                "K9O_MISSING_PROJECT_CONTEXT",
                "client.gate operations require projectSlug bound on the client",
                Map.of(), null, null, null
            );
        }
        return config.projectSlug;
    }

    public Envelope<JsonNode> signUp(SignUpInput input) {
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(requireProject(), "gate/signup"),
            input, null, new TypeReference<Envelope<JsonNode>>() {});
    }

    public Envelope<JsonNode> signIn(SignInInput input) {
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(requireProject(), "gate/signin"),
            input, null, new TypeReference<Envelope<JsonNode>>() {});
    }

    public Envelope<JsonNode> signOut(SignOutInput input) {
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(requireProject(), "gate/signout"),
            input, null, new TypeReference<Envelope<JsonNode>>() {});
    }

    public Envelope<JsonNode> whoami() {
        return HttpTransport.request(config, "GET",
            UrlGrammar.projectPath(requireProject(), "gate/whoami"),
            null, null, new TypeReference<Envelope<JsonNode>>() {});
    }

    public Envelope<JsonNode> refreshToken(RefreshTokenInput input) {
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(requireProject(), "gate/refresh"),
            input, null, new TypeReference<Envelope<JsonNode>>() {});
    }

    public Envelope<JsonNode> verifySession() {
        return HttpTransport.request(config, "GET",
            UrlGrammar.projectPath(requireProject(), "gate/verify"),
            null, null, new TypeReference<Envelope<JsonNode>>() {});
    }

    /**
     * The project's hosted sign-in page URL and WebAuthn {@code rpId}. Unauthenticated and cacheable: NO credential
     * is sent (no Authorization, no cookie), even when an API key is configured. Take both values as given — an
     * unknown org, a deleted project and a non-Gate project all answer the same 404 K9O_PROJECT_NOT_FOUND.
     */
    public Envelope<HostedGateInfo> hosted() {
        return HttpTransport.request(config, "GET",
            UrlGrammar.projectPath(requireProject(), "gate/hosted"),
            null, null, null, true,
            HttpTransport.JSON.getTypeFactory().constructType(new TypeReference<Envelope<HostedGateInfo>>() {}));
    }
}

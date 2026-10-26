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

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
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
}

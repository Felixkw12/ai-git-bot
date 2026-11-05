package org.remus.giteabot.ai.opper;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderRegistry;
import org.remus.giteabot.ai.AiProviderType;
import org.remus.giteabot.ai.ModelFlavor;
import org.remus.giteabot.ai.openai.OpenAiClient;
import org.remus.giteabot.ai.openai.OpenAiProviderMetadata;
import org.remus.giteabot.config.OpenAiProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Opper reuses the OpenAI Chat Completions client, but its endpoint sits at
 * {@code /chat/completions} below the API root rather than at
 * {@code /v1/chat/completions}.
 */
class OpperProviderMetadataTest {

    private static final String OK = """
            {"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}
            """;

    private static ObjectProvider<RestClient.Builder> builders(RestClient.Builder http) {
        return new ObjectProvider<>() {
            @Override
            public RestClient.@NonNull Builder getObject() {
                return http;
            }
        };
    }

    private static AiIntegration integration(String flavor) {
        AiIntegration integration = new AiIntegration();
        integration.setName("opper");
        integration.setProviderType(OpperProviderMetadata.PROVIDER_TYPE);
        integration.setApiUrl(OpperProviderMetadata.DEFAULT_API_URL);
        integration.setModel("claude-sonnet-5-5");
        integration.setMaxTokens(8192);
        integration.setModelFlavor(flavor);
        return integration;
    }

    private static AiClient clientFor(AiIntegration integration, MockRestServiceServer[] serverHolder) {
        RestClient.Builder http = RestClient.builder();
        serverHolder[0] = MockRestServiceServer.bindTo(http).build();
        OpperProviderMetadata provider = new OpperProviderMetadata(builders(http));
        return provider.createClient(provider.buildRestClient(integration, "test-key"), integration);
    }

    @Test
    void postsChatCompletionsBelowTheCompatRootWithBearerKey() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        AiClient client = clientFor(integration("standard"), server);
        server[0].expect(requestTo("https://api.opper.ai/v3/compat/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.max_completion_tokens").value(8192))
                .andExpect(jsonPath("$.reasoning_effort").doesNotExist())
                .andExpect(jsonPath("$.chat_template_kwargs").doesNotExist())
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.chatWithTools(List.of(), "review this PR", List.of(), "You are a reviewer.", null, null);

        server[0].verify();
    }

    @Test
    void noReasoningFlavorSendsReasoningEffortNone() {
        MockRestServiceServer[] server = new MockRestServiceServer[1];
        AiClient client = clientFor(integration("no_reasoning"), server);
        server[0].expect(requestTo("https://api.opper.ai/v3/compat/chat/completions"))
                .andExpect(jsonPath("$.reasoning_effort").value("none"))
                .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        client.chatWithTools(List.of(), "review this PR", List.of(), "You are a reviewer.", null, null);

        server[0].verify();
    }

    @Test
    void exposesDefaultsAndOpenAiFlavorsStampedWithOpper() {
        OpperProviderMetadata provider = new OpperProviderMetadata(builders(RestClient.builder()));

        assertEquals(AiProviderType.OPPER, provider.getProviderType());
        assertEquals("https://api.opper.ai/v3/compat", provider.getDefaultApiUrl());
        assertEquals(OpperProviderMetadata.SUGGESTED_MODELS, provider.getSuggestedModels());
        assertTrue(provider.requiresApiKey());
        List<ModelFlavor> flavors = provider.getFlavors();
        assertEquals(List.of("standard", "no_reasoning"), flavors.stream().map(ModelFlavor::id).toList());
        assertTrue(flavors.stream().allMatch(f -> f.providerType() == AiProviderType.OPPER));
        assertInstanceOf(OpenAiClient.class,
                provider.createClient(RestClient.builder().build(), integration("standard")));
    }

    @Test
    void buildRestClientRequiresAnApiKey() {
        OpperProviderMetadata provider = new OpperProviderMetadata(builders(RestClient.builder()));

        assertThrows(IllegalStateException.class, () -> provider.buildRestClient(integration("standard"), " "));
    }

    @Test
    void registryListsOpperAfterOpenAi() {
        AiProviderRegistry registry = new AiProviderRegistry(List.of(
                new OpperProviderMetadata(builders(RestClient.builder())),
                new OpenAiProviderMetadata(builders(RestClient.builder()), new OpenAiProperties())
        ));

        assertEquals(List.of(AiProviderType.OPENAI, AiProviderType.OPPER), registry.getProviderTypes());
        assertEquals("https://api.opper.ai/v3/compat", registry.getDefaultApiUrls().get(AiProviderType.OPPER));
    }
}

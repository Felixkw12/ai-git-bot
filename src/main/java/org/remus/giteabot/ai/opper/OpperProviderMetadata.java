package org.remus.giteabot.ai.opper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.admin.AiIntegration;
import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.ai.AiProviderMetadata;
import org.remus.giteabot.ai.AiProviderType;
import org.remus.giteabot.ai.ModelFlavor;
import org.remus.giteabot.ai.openai.OpenAiClient;
import org.remus.giteabot.ai.openai.OpenAiFlavor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;

/**
 * Metadata and factory for Opper, an AI gateway. Opper serves the OpenAI Chat
 * Completions format at {@code /chat/completions} below its API root, so the
 * generic {@link OpenAiClient} is reused with that path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpperProviderMetadata implements AiProviderMetadata {

    public static final AiProviderType PROVIDER_TYPE = AiProviderType.OPPER;
    public static final String DEFAULT_API_URL = "https://api.opper.ai/v3/compat";
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    // Model ids checked against https://api.opper.ai/v3/models on 2026-11-05.
    public static final List<String> SUGGESTED_MODELS = List.of(
            "claude-sonnet-5-5",
            "gpt-6.1-sol",
            "gemini-3.8-flash",
            "deepseek-v4.1-flash"
    );

    private final ObjectProvider<RestClient.Builder> restClientBuilder;

    @Override
    public AiProviderType getProviderType() {
        return PROVIDER_TYPE;
    }

    @Override
    public String getDefaultApiUrl() {
        return DEFAULT_API_URL;
    }

    @Override
    public List<String> getSuggestedModels() {
        return SUGGESTED_MODELS;
    }

    @Override
    public boolean requiresApiKey() {
        return true;
    }

    @Override
    public RestClient buildRestClient(AiIntegration integration, String decryptedApiKey) {
        if (decryptedApiKey == null || decryptedApiKey.isBlank()) {
            throw new IllegalStateException("Opper integration requires an API key");
        }
        return restClientBuilder.getObject()
                .baseUrl(integration.getApiUrl())
                .defaultHeader("Authorization", "Bearer " + decryptedApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public List<ModelFlavor> getFlavors() {
        return OpenAiFlavor.modelFlavors(PROVIDER_TYPE);
    }

    @Override
    public AiClient createClient(RestClient restClient, AiIntegration integration) {
        String rawFlavor = integration.getModelFlavor();
        String storedFlavor = (rawFlavor == null || rawFlavor.isBlank())
                ? null
                : rawFlavor.trim().toLowerCase(Locale.ROOT);
        OpenAiFlavor flavor = OpenAiFlavor.fromId(storedFlavor);
        if (storedFlavor != null && !OpenAiFlavor.STANDARD.getId().equals(storedFlavor)
                && flavor == OpenAiFlavor.STANDARD) {
            log.warn("Unknown model flavor '{}' for Opper integration '{}'; using 'standard'",
                    rawFlavor, integration.getName());
        }
        // chat_template_kwargs is a setting for self-hosted servers behind the OpenAI provider; Opper gets none.
        return new OpenAiClient(
                restClient,
                integration.getModel(),
                integration.getMaxTokens(),
                !integration.isUseLegacyToolCalling(),
                flavor,
                null,
                CHAT_COMPLETIONS_PATH
        );
    }
}

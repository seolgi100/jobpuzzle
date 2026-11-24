package com.example.jobpuzzle.analysis.rag.embedding;

import com.example.jobpuzzle.analysis.rag.config.RagProperties;
import com.example.jobpuzzle.global.error.CustomException;
import com.example.jobpuzzle.global.error.ErrorCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

/** OpenAI embeddings API를 내부 EmbeddingProvider 계약으로 제한한다. */
@Component
@ConditionalOnProperty(prefix = "app.rag", name = "mode", havingValue = "real")
public class OpenAiEmbeddingProvider implements EmbeddingProvider {
    private final RagProperties properties;
    private final RestClient client;

    public OpenAiEmbeddingProvider(RagProperties properties, @Qualifier("openAiEmbeddingRestClient") RestClient client) {
        this.properties = properties;
        String key = properties.getOpenai().getApiKey();
        if (key == null || key.isBlank()) throw new CustomException(ErrorCode.EMBEDDING_PROVIDER_ERROR, "OpenAI API key is missing");
        this.client = client;
    }

    @Override public EmbeddingResult embed(String text) {
        try {
            Response response = client.post().uri("/v1/embeddings")
                    .body(new Request(properties.getEmbedding().getModel(), text, properties.getEmbedding().getDimension())).retrieve().body(Response.class);
            if (response == null || response.data() == null || response.data().size() != 1 || response.data().get(0).embedding() == null)
                throw new CustomException(ErrorCode.EMBEDDING_PROVIDER_ERROR, "OpenAI embedding response is invalid");
            double[] vector = response.data().get(0).embedding().stream().mapToDouble(Double::doubleValue).toArray();
            if (vector.length != properties.getEmbedding().getDimension())
                throw new CustomException(ErrorCode.VECTOR_COLLECTION_INCOMPATIBLE, "embedding dimension mismatch");
            return new EmbeddingResult(getProviderName(), getModelName(), vector.length, vector);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 429) throw new CustomException(ErrorCode.EMBEDDING_RATE_LIMITED);
            throw new CustomException(ErrorCode.EMBEDDING_PROVIDER_ERROR, "OpenAI embedding request failed");
        } catch (CustomException e) { throw e;
        } catch (RuntimeException e) { throw new CustomException(ErrorCode.EMBEDDING_PROVIDER_ERROR, "OpenAI embedding request failed"); }
    }
    @Override public String getProviderName() { return properties.getEmbedding().getProvider(); }
    @Override public String getModelName() { return properties.getEmbedding().getModel(); }
    @Override public int getDimension() { return properties.getEmbedding().getDimension(); }
    private record Request(String model, String input, int dimensions) {}
    private record Response(List<Data> data) {}
    private record Data(List<Double> embedding) {}
}

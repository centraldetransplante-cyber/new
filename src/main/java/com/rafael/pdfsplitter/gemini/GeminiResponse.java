package com.rafael.pdfsplitter.gemini;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiResponse(List<Candidate> candidates) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(GeminiRequest.Content content) {
    }

    public String primeiroTexto() {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        GeminiRequest.Content content = candidates.get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            return "";
        }
        return content.parts().get(0).text();
    }
}

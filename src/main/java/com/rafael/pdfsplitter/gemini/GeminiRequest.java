package com.rafael.pdfsplitter.gemini;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiRequest(List<Content> contents, GenerationConfig generationConfig) {

    public record Content(List<Part> parts) {
    }

    public record Part(String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerationConfig(Double temperature, Integer maxOutputTokens) {
    }

    public static GeminiRequest deTexto(String texto) {
        return new GeminiRequest(List.of(new Content(List.of(new Part(texto)))), null);
    }

    /** Usado para o agrupamento (resposta mais previsível/determinística e limite de tamanho conhecido). */
    public static GeminiRequest deTexto(String texto, int maxOutputTokens) {
        return new GeminiRequest(List.of(new Content(List.of(new Part(texto)))),
                new GenerationConfig(0.0, maxOutputTokens));
    }
}

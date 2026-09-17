package com.rafael.pdfsplitter.gemini;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GeminiResponse(List<Candidate> candidates) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(GeminiRequest.Content content, String finishReason) {
    }

    /**
     * Concatena o texto de todas as partes que NÃO são resumo de "pensamento" ({@code thought=true}) — ler só
     * {@code parts[0]} quebraria se a API algum dia devolver o pensamento como uma parte separada antes da
     * resposta de verdade (a JSON ficaria em {@code parts[1]}, silenciosamente ignorada).
     */
    public String primeiroTexto() {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        GeminiRequest.Content content = candidates.get(0).content();
        if (content == null || content.parts() == null) {
            return "";
        }
        StringBuilder texto = new StringBuilder();
        for (GeminiRequest.Part parte : content.parts()) {
            if (parte == null || Boolean.TRUE.equals(parte.thought()) || parte.text() == null) {
                continue;
            }
            texto.append(parte.text());
        }
        return texto.toString();
    }

    /** {@code null} se não houver candidato — útil pra diagnosticar uma resposta vazia (ex.: MAX_TOKENS). */
    public String primeiroFinishReason() {
        return candidates == null || candidates.isEmpty() ? null : candidates.get(0).finishReason();
    }
}

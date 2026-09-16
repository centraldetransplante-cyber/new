package com.rafael.pdfsplitter.gemini;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeminiRequest(List<Content> contents, GenerationConfig generationConfig) {

    public record Content(List<Part> parts) {
    }

    public record Part(String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GenerationConfig(Double temperature, Integer maxOutputTokens, String responseMimeType,
            Schema responseSchema) {
    }

    /**
     * Subconjunto do formato de schema OpenAPI que a API do Gemini aceita em {@code responseSchema} para forçar
     * saída JSON estruturada (ver {@link #deJsonAgrupamento}) — evita que o parsing dependa de o modelo respeitar
     * um formato de texto livre (o motivo do parsing por regex em {@code InterpretadorAgrupamento} ter existido).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Schema(String type, Map<String, Schema> properties, Schema items,
            @JsonProperty("enum") List<String> enumValores, List<String> required) {
    }

    public static GeminiRequest deTexto(String texto) {
        return new GeminiRequest(List.of(new Content(List.of(new Part(texto)))), null);
    }

    /**
     * Usado para o agrupamento: força a resposta do Gemini a ser um array JSON de
     * {@code {pagina, documento, categoria}} respeitando exatamente o schema (em vez de confiar num formato de
     * texto livre "numero|numero|CATEGORIA" e parsear com regex) — contrato determinístico, à prova de variação
     * de formatação do modelo.
     */
    public static GeminiRequest deJsonAgrupamento(String texto, int maxOutputTokens, List<String> categoriasValidas) {
        Schema itemSchema = new Schema("OBJECT", Map.of(
                "pagina", new Schema("INTEGER", null, null, null, null),
                "documento", new Schema("INTEGER", null, null, null, null),
                "categoria", new Schema("STRING", null, null, categoriasValidas, null)),
                null, null, List.of("pagina", "documento", "categoria"));
        Schema arraySchema = new Schema("ARRAY", null, itemSchema, null, null);
        return new GeminiRequest(List.of(new Content(List.of(new Part(texto)))),
                new GenerationConfig(0.0, maxOutputTokens, "application/json", arraySchema));
    }
}

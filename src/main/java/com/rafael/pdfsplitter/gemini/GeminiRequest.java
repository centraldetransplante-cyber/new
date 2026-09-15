package com.rafael.pdfsplitter.gemini;

import java.util.List;

public record GeminiRequest(List<Content> contents) {

    public record Content(List<Part> parts) {
    }

    public record Part(String text) {
    }

    public static GeminiRequest deTexto(String texto) {
        return new GeminiRequest(List.of(new Content(List.of(new Part(texto)))));
    }
}

package com.rafael.pdfsplitter;

public enum MetodoClassificacao {
    GEMINI,
    PALAVRA_CHAVE,
    REGRA_BLOCO_TFD,
    /** Página sem texto extraível (provável digitalização sem OCR) — nem o Gemini nem palavra-chave rodam. */
    SEM_TEXTO
}

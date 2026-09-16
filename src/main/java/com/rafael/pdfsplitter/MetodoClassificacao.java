package com.rafael.pdfsplitter;

public enum MetodoClassificacao {
    /** Página classificada isoladamente pelo Gemini (modo PAGINA, ou fallback per-page dentro do modo CONTEXTO). */
    GEMINI,
    /** Documento inteiro (várias páginas) agrupado e classificado pelo Gemini com contexto (modo CONTEXTO). */
    GEMINI_CONTEXTO,
    PALAVRA_CHAVE,
    REGRA_BLOCO_TFD,
    /** Página sem texto extraível (provável digitalização sem OCR) — nem o Gemini nem palavra-chave rodam. */
    SEM_TEXTO
}

package com.rafael.pdfsplitter;

public enum MetodoClassificacao {
    /** Página classificada isoladamente pelo Gemini (modo PAGINA, ou fallback per-page dentro do modo CONTEXTO). */
    GEMINI,
    /** Documento inteiro (várias páginas) agrupado e classificado pelo Gemini com contexto (modo CONTEXTO). */
    GEMINI_CONTEXTO,
    /**
     * Fallback genuíno: o Gemini FALHOU (erro de rede/API, resposta vazia, cobertura insuficiente numa janela) e a
     * categoria veio só de busca por palavra-chave em Java, sem nenhum julgamento de conteúdo do modelo.
     */
    PALAVRA_CHAVE,
    /**
     * O Gemini respondeu normalmente, mas uma regra de negócio em Java rebaixou ou reroteou a categoria que ele deu
     * (ex.: TFD_RS sem marcador do RS virando OUTROS, ou uma página com RG/CPF própria sendo arrancada de um
     * documento TFD) - diferente de {@link #PALAVRA_CHAVE}, aqui a IA funcionou; não deve contar como "não
     * classificado pela IA" no resumo mostrado ao usuário.
     */
    REGRA_JAVA,
    REGRA_BLOCO_TFD,
    /** Página sem texto extraível (provável digitalização sem OCR) — nem o Gemini nem palavra-chave rodam. */
    SEM_TEXTO
}

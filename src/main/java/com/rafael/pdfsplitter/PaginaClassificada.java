package com.rafael.pdfsplitter;

/**
 * Resultado da classificação de UMA página, junto com os sinais auxiliares
 * usados pela regra de bloco do TFD (texto normalizado da página e se a
 * página parece ser o INÍCIO de um documento ou apenas a CONTINUAÇÃO do
 * documento anterior).
 */
public class PaginaClassificada {
    private Categoria categoria;
    private MetodoClassificacao metodo;

    /** Texto da página já em minúsculas e sem acentos (usado pelas regras de bloco). */
    private final String textoNormalizado;

    /**
     * Sinal vindo do Gemini dizendo se a página é o INÍCIO de um documento
     * (tem cabeçalho/timbre/título de formulário) ou a CONTINUAÇÃO do
     * documento anterior. {@code null} quando não há esse sinal (fallback por
     * palavra-chave ou resposta do Gemini sem o marcador).
     */
    private final Boolean inicioDocumento;

    public PaginaClassificada(Categoria categoria, MetodoClassificacao metodo) {
        this(categoria, metodo, "", null);
    }

    public PaginaClassificada(Categoria categoria, MetodoClassificacao metodo, String textoNormalizado,
            Boolean inicioDocumento) {
        this.categoria = categoria;
        this.metodo = metodo;
        this.textoNormalizado = textoNormalizado == null ? "" : textoNormalizado;
        this.inicioDocumento = inicioDocumento;
    }

    public Categoria getCategoria() {
        return categoria;
    }

    public void setCategoria(Categoria categoria) {
        this.categoria = categoria;
    }

    public MetodoClassificacao getMetodo() {
        return metodo;
    }

    public void setMetodo(MetodoClassificacao metodo) {
        this.metodo = metodo;
    }

    public String getTextoNormalizado() {
        return textoNormalizado;
    }

    public Boolean getInicioDocumento() {
        return inicioDocumento;
    }
}

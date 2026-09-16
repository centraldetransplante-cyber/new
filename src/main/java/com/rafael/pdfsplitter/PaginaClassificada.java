package com.rafael.pdfsplitter;

public class PaginaClassificada {
    private Categoria categoria;
    private MetodoClassificacao metodo;

    public PaginaClassificada(Categoria categoria, MetodoClassificacao metodo) {
        this.categoria = categoria;
        this.metodo = metodo;
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
}

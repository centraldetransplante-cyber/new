package com.rafael.pdfsplitter;

import java.util.EnumSet;
import java.util.Set;

public enum Categoria {
    TFD_RS("tfd_rs"),
    TFD_OUTROS_ESTADOS("tfd_outros_estados"),
    PROTOCOLO_ENCAMINHAMENTO("protocolo_encaminhamento"),
    EXAMES("exames"),
    DOCUMENTOS("documentos"),
    OUTROS("outros");

    /**
     * Categorias de TFD que participam da regra de bloco/costura entre janelas — definidas uma única vez aqui (em
     * vez de um {@code EnumSet} duplicado em cada classe que precisa dele) para não haver risco das duas cópias
     * divergirem ao adicionar/remover uma categoria de TFD.
     */
    public static final Set<Categoria> CATEGORIAS_TFD = EnumSet.of(TFD_RS, TFD_OUTROS_ESTADOS);

    private final String pastaSaida;

    Categoria(String pastaSaida) {
        this.pastaSaida = pastaSaida;
    }

    public String getPastaSaida() {
        return pastaSaida;
    }
}

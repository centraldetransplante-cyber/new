package com.rafael.pdfsplitter;

public enum Categoria {
    TFD_RS("tfd_rs"),
    TFD_OUTROS_ESTADOS("tfd_outros_estados"),
    PROTOCOLO_ENCAMINHAMENTO("protocolo_encaminhamento"),
    EXAMES("exames"),
    DOCUMENTOS("documentos"),
    OUTROS("outros");

    private final String pastaSaida;

    Categoria(String pastaSaida) {
        this.pastaSaida = pastaSaida;
    }

    public String getPastaSaida() {
        return pastaSaida;
    }
}

package com.rafael.pdfsplitter;

/** PDF de entrada inválido, corrompido, protegido por senha ou sem páginas — vira HTTP 400 para o cliente. */
public class PdfInvalidoException extends RuntimeException {
    public PdfInvalidoException(String mensagem) {
        super(mensagem);
    }

    public PdfInvalidoException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}

package com.rafael.pdfsplitter.gemini;

import com.rafael.pdfsplitter.Categoria;

/** Um documento físico (sequência de páginas consecutivas do mesmo pedido/documento) detectado pelo Gemini. */
public record DocumentoDetectado(int paginaInicial, int paginaFinal, Categoria categoria) {
}

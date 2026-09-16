package com.rafael.pdfsplitter.gemini;

import java.util.List;
import java.util.Set;

/**
 * Resultado de agrupar uma janela de páginas em documentos. {@code paginasNaoResolvidas} são páginas da janela
 * para as quais o Gemini não devolveu uma linha reconhecível — o chamador decide se o restante da janela tem
 * cobertura suficiente para confiar no resultado ou se cai no fallback por palavra-chave.
 */
public record ResultadoAgrupamento(List<DocumentoDetectado> documentos, Set<Integer> paginasNaoResolvidas) {
}

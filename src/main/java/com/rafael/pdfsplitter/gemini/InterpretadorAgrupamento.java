package com.rafael.pdfsplitter.gemini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import com.rafael.pdfsplitter.Categoria;

/**
 * Interpreta a resposta do Gemini para o agrupamento de uma janela de páginas em documentos, no formato (uma linha
 * por página):
 *
 * <pre>numero_da_pagina|numero_do_documento|CATEGORIA</pre>
 *
 * É deliberadamente tolerante: processa linha a linha, ignora qualquer linha que não bata no padrão (preâmbulo,
 * cercas de markdown, linha em branco) em vez de invalidar a resposta inteira — um formato rígido (JSON, por
 * exemplo) faz um único erro de sintaxe no fim de uma resposta longa jogar fora tudo; aqui uma linha perdida vira
 * só uma página não resolvida, identificável e recuperável.
 */
public final class InterpretadorAgrupamento {

    private static final Logger LOG = Logger.getLogger(InterpretadorAgrupamento.class);

    private static final Pattern LINHA = Pattern.compile("^\\s*(\\d{1,5})\\s*[|;,\\t]\\s*D?0*(\\d{1,5})\\s*[|;,\\t]\\s*(.+?)\\s*$");

    private InterpretadorAgrupamento() {
    }

    public static ResultadoAgrupamento interpretar(String resposta, int primeiraPagina, int ultimaPagina) {
        Map<Integer, int[]> paginaParaDocIdECategoriaIdx = new LinkedHashMap<>();
        Map<Integer, Categoria> paginaParaCategoria = new LinkedHashMap<>();

        if (resposta != null) {
            for (String linha : resposta.split("\\r?\\n")) {
                Matcher m = LINHA.matcher(linha);
                if (!m.matches()) {
                    continue;
                }
                int pagina;
                int docId;
                try {
                    pagina = Integer.parseInt(m.group(1));
                    docId = Integer.parseInt(m.group(2));
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pagina < primeiraPagina || pagina > ultimaPagina) {
                    continue;
                }
                if (paginaParaCategoria.containsKey(pagina)) {
                    continue; // pagina duplicada - vale a primeira ocorrencia
                }
                Categoria categoria = interpretarCategoria(m.group(3));
                if (categoria == null) {
                    continue;
                }
                paginaParaDocIdECategoriaIdx.put(pagina, new int[] { docId });
                paginaParaCategoria.put(pagina, categoria);
            }
        }

        List<DocumentoDetectado> documentos = new ArrayList<>();
        TreeSet<Integer> naoResolvidas = new TreeSet<>();

        Integer inicioGrupo = null;
        Integer fimGrupo = null;
        Integer docIdGrupo = null;
        Categoria categoriaGrupo = null;

        for (int pagina = primeiraPagina; pagina <= ultimaPagina; pagina++) {
            int[] docIdBox = paginaParaDocIdECategoriaIdx.get(pagina);
            if (docIdBox == null) {
                naoResolvidas.add(pagina);
                if (inicioGrupo != null) {
                    documentos.add(new DocumentoDetectado(inicioGrupo, fimGrupo, categoriaGrupo));
                    inicioGrupo = null;
                }
                continue;
            }

            int docId = docIdBox[0];
            Categoria categoria = paginaParaCategoria.get(pagina);

            boolean continuaGrupoAtual = inicioGrupo != null && fimGrupo == pagina - 1 && docIdGrupo == docId;
            if (continuaGrupoAtual) {
                if (categoria != categoriaGrupo) {
                    LOG.warnf("Pagina %d tem o mesmo numero de documento (%d) que a pagina anterior mas categoria "
                            + "diferente (%s vs %s) - mantendo a categoria da 1a pagina do documento",
                            pagina, docId, categoria, categoriaGrupo);
                }
                fimGrupo = pagina;
            } else {
                if (inicioGrupo != null) {
                    documentos.add(new DocumentoDetectado(inicioGrupo, fimGrupo, categoriaGrupo));
                }
                inicioGrupo = pagina;
                fimGrupo = pagina;
                docIdGrupo = docId;
                categoriaGrupo = categoria;
            }
        }
        if (inicioGrupo != null) {
            documentos.add(new DocumentoDetectado(inicioGrupo, fimGrupo, categoriaGrupo));
        }

        return new ResultadoAgrupamento(documentos, naoResolvidas);
    }

    private static Categoria interpretarCategoria(String textoCategoria) {
        String limpo = textoCategoria.toUpperCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "_")
                .replaceAll("[^A-Z_]", "");

        Categoria melhorMatch = null;
        for (Categoria categoria : Categoria.values()) {
            if (limpo.contains(categoria.name())) {
                if (melhorMatch == null || categoria.name().length() > melhorMatch.name().length()) {
                    melhorMatch = categoria;
                }
            }
        }
        return melhorMatch;
    }
}

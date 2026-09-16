package com.rafael.pdfsplitter.gemini;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafael.pdfsplitter.Categoria;

/**
 * Interpreta a resposta do Gemini para o agrupamento de uma janela de páginas em documentos. A chamada que gera
 * essa resposta usa {@code responseSchema} (ver {@link GeminiRequest#deJsonAgrupamento}) para forçar um array JSON
 * de {@code {"pagina": N, "documento": N, "categoria": "..."}} — um item por página — em vez de um formato de
 * texto livre parseado por regex, então o parsing aqui é apenas desserialização, sem heurística de formato.
 *
 * Ainda assim é deliberadamente tolerante a item por item: um item com {@code categoria} não reconhecida ou
 * {@code pagina} fora da janela é descartado individualmente (vira uma página não resolvida), em vez de a
 * resposta inteira ser jogada fora — por exemplo se a saída for truncada por atingir {@code maxOutputTokens} num
 * PDF com páginas muito longas, o JSON pode vir incompleto/inválido; nesse caso a resposta inteira não é
 * aproveitável e todas as páginas da janela ficam como não resolvidas (o chamador cai no fallback por
 * palavra-chave para essa janela).
 */
public final class InterpretadorAgrupamento {

    private static final Logger LOG = Logger.getLogger(InterpretadorAgrupamento.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InterpretadorAgrupamento() {
    }

    /** Um item do array JSON devolvido pelo Gemini, no formato do schema em {@link GeminiRequest#deJsonAgrupamento}. */
    private record ItemAgrupamento(Integer pagina, Integer documento, String categoria) {
    }

    public static ResultadoAgrupamento interpretar(String respostaJson, int primeiraPagina, int ultimaPagina) {
        List<ItemAgrupamento> itens = parsearJson(respostaJson);

        Map<Integer, Integer> paginaParaDocId = new LinkedHashMap<>();
        Map<Integer, Categoria> paginaParaCategoria = new LinkedHashMap<>();

        for (ItemAgrupamento item : itens) {
            if (item == null || item.pagina() == null || item.documento() == null || item.categoria() == null) {
                continue;
            }
            int pagina = item.pagina();
            if (pagina < primeiraPagina || pagina > ultimaPagina) {
                continue;
            }
            if (paginaParaCategoria.containsKey(pagina)) {
                continue; // pagina duplicada - vale a primeira ocorrencia
            }
            Categoria categoria = interpretarCategoria(item.categoria());
            if (categoria == null) {
                continue;
            }
            paginaParaDocId.put(pagina, item.documento());
            paginaParaCategoria.put(pagina, categoria);
        }

        return montarResultado(paginaParaDocId, paginaParaCategoria, primeiraPagina, ultimaPagina);
    }

    private static List<ItemAgrupamento> parsearJson(String respostaJson) {
        if (respostaJson == null || respostaJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(respostaJson, new TypeReference<List<ItemAgrupamento>>() {
            });
        } catch (Exception e) {
            LOG.warnf(e, "Resposta de agrupamento do Gemini não é um JSON válido (possivelmente truncada) - "
                    + "tratando a janela inteira como não resolvida: %s", respostaJson);
            return List.of();
        }
    }

    private static ResultadoAgrupamento montarResultado(Map<Integer, Integer> paginaParaDocId,
            Map<Integer, Categoria> paginaParaCategoria, int primeiraPagina, int ultimaPagina) {
        List<DocumentoDetectado> documentos = new ArrayList<>();
        TreeSet<Integer> naoResolvidas = new TreeSet<>();

        Integer inicioGrupo = null;
        Integer fimGrupo = null;
        Integer docIdGrupo = null;
        Categoria categoriaGrupo = null;

        for (int pagina = primeiraPagina; pagina <= ultimaPagina; pagina++) {
            Integer docId = paginaParaDocId.get(pagina);
            if (docId == null) {
                naoResolvidas.add(pagina);
                if (inicioGrupo != null) {
                    documentos.add(new DocumentoDetectado(inicioGrupo, fimGrupo, categoriaGrupo));
                    inicioGrupo = null;
                }
                continue;
            }

            Categoria categoria = paginaParaCategoria.get(pagina);

            boolean continuaGrupoAtual = inicioGrupo != null && fimGrupo == pagina - 1 && docIdGrupo.equals(docId);
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

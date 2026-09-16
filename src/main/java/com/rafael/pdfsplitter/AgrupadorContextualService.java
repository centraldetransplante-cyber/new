package com.rafael.pdfsplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jboss.logging.Logger;

import com.rafael.pdfsplitter.gemini.DocumentoDetectado;
import com.rafael.pdfsplitter.gemini.GeminiClassificadorService;
import com.rafael.pdfsplitter.gemini.ResultadoAgrupamento;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Classificação com contexto (modo {@code classificador.modo=CONTEXTO}, o padrão): em vez de classificar cada
 * página isolada e tentar remendar blocos de TFD depois (modo {@code PAGINA}, ver {@link PdfSplitService}), manda
 * ao Gemini janelas de várias páginas de uma vez — com as páginas anteriores como contexto — e pede para ele
 * AGRUPAR diretamente em documentos. Isso resolve na raiz o bug que motivou esse redesenho: um anexo com timbre
 * próprio (ex.: laudo médico de outro estado dentro de um pedido de TFD/RS) sendo confundido com a capa de um
 * pedido novo, porque o modelo agora vê várias páginas em sequência, não uma por vez.
 */
@ApplicationScoped
public class AgrupadorContextualService {

    private static final Logger LOG = Logger.getLogger(AgrupadorContextualService.class);

    private static final Set<Categoria> CATEGORIAS_TFD = Categoria.CATEGORIAS_TFD;

    /** Abaixo dessa fração de páginas resolvidas numa janela, a janela inteira é tratada como falha. */
    private static final double COBERTURA_MINIMA = 0.6;

    private final ClassificadorConfig config;
    private final GeminiClassificadorService geminiService;
    private final ClassificadorPalavraChaveService palavraChave;

    public AgrupadorContextualService(ClassificadorConfig config, GeminiClassificadorService geminiService,
            ClassificadorPalavraChaveService palavraChave) {
        this.config = config;
        this.geminiService = geminiService;
        this.palavraChave = palavraChave;
    }

    public List<PaginaClassificada> classificar(List<String> textosPorPagina) {
        int total = textosPorPagina.size();
        List<String> normalizados = new ArrayList<>(total);
        for (String texto : textosPorPagina) {
            normalizados.add(palavraChave.normalizar(texto));
        }

        Categoria[] categoriaFinal = new Categoria[total];
        MetodoClassificacao[] metodoFinal = new MetodoClassificacao[total];

        int porJanela = Math.max(1, config.contextoPaginasPorJanela());
        int paginasDeContexto = Math.max(0, config.contextoPaginasDeContexto());
        int maxChars = Math.max(200, config.contextoMaxCaracteresPorPagina());

        Categoria categoriaAbertaAnterior = null;
        int paginaFinalAbertaAnterior = -1;
        boolean rsConfirmadoAnterior = false;

        int inicioJanela = 0; // 0-based, indice da 1a pagina de decisao da janela
        while (inicioJanela < total) {
            int tamanhoJanela = Math.min(porJanela, total - inicioJanela);
            int inicioContexto = Math.max(0, inicioJanela - paginasDeContexto);

            List<String> textosContexto = new ArrayList<>();
            for (int k = inicioContexto; k < inicioJanela; k++) {
                textosContexto.add(truncar(textosPorPagina.get(k), maxChars));
            }
            List<String> textosDecisao = new ArrayList<>();
            for (int k = inicioJanela; k < inicioJanela + tamanhoJanela; k++) {
                textosDecisao.add(truncar(textosPorPagina.get(k), maxChars));
            }

            int primeiraPaginaDecisao = inicioJanela + 1;
            int ultimaPaginaDecisao = inicioJanela + tamanhoJanela;

            ResultadoAgrupamento resultado = geminiService.disponivel()
                    ? geminiService.agrupar(textosContexto, inicioContexto + 1, textosDecisao, primeiraPaginaDecisao)
                    : null;

            double cobertura = resultado == null ? 0.0
                    : 1.0 - (resultado.paginasNaoResolvidas().size() / (double) tamanhoJanela);

            if (resultado == null || cobertura < COBERTURA_MINIMA) {
                LOG.warnf("Janela de páginas %d-%d sem cobertura suficiente do Gemini (%.0f%%) - usando fallback "
                        + "por palavra-chave para essa janela", primeiraPaginaDecisao, ultimaPaginaDecisao, cobertura * 100);
                for (int k = inicioJanela; k < inicioJanela + tamanhoJanela; k++) {
                    categoriaFinal[k] = normalizados.get(k).isBlank()
                            ? Categoria.OUTROS
                            : palavraChave.classificar(normalizados.get(k));
                    metodoFinal[k] = normalizados.get(k).isBlank() ? MetodoClassificacao.SEM_TEXTO
                            : MetodoClassificacao.PALAVRA_CHAVE;
                }
                // Não tenta costurar um bloco de TFD por cima de um trecho que caiu no fallback.
                categoriaAbertaAnterior = null;
                paginaFinalAbertaAnterior = -1;
                rsConfirmadoAnterior = false;
            } else {
                for (DocumentoDetectado doc : resultado.documentos()) {
                    Categoria categoriaBruta = doc.categoria();

                    // Continua o documento aberto na janela anterior se for a categoria de TFD
                    // que o Gemini deu (ANTES de qualquer rebaixamento) e for contíguo — é essa
                    // continuidade que decide se um TFD_RS sem marcador próprio (ex.: o laudo
                    // médico anexado, que não menciona o RS) ainda conta como confirmado por
                    // herdar a confirmação do documento que ele continua.
                    boolean continuaDocumentoAnterior = categoriaAbertaAnterior != null
                            && doc.paginaInicial() == paginaFinalAbertaAnterior + 1
                            && categoriaBruta == categoriaAbertaAnterior
                            && CATEGORIAS_TFD.contains(categoriaBruta);

                    Categoria categoriaEfetiva = categoriaBruta;
                    boolean rsConfirmadoAqui = false;
                    if (categoriaBruta == Categoria.TFD_RS) {
                        rsConfirmadoAqui = algumaPaginaConfirmaRs(normalizados, doc.paginaInicial(), doc.paginaFinal());
                        if (!rsConfirmadoAqui && continuaDocumentoAnterior && rsConfirmadoAnterior) {
                            // Nenhuma página DESTE trecho bate no marcador do RS, mas ele é a
                            // continuação direta de um documento que já tinha sido confirmado -
                            // exatamente o caso do laudo médico de outro estado anexado ao pedido
                            // do RS, que naturalmente não menciona o RS.
                            rsConfirmadoAqui = true;
                        }
                        if (!rsConfirmadoAqui) {
                            LOG.warnf("Documento páginas %d-%d marcado TFD_RS pelo Gemini mas nenhuma página (nem a "
                                    + "do documento anterior continuado) bate no marcador do RS - rebaixando para "
                                    + "TFD_OUTROS_ESTADOS", doc.paginaInicial(), doc.paginaFinal());
                            categoriaEfetiva = Categoria.TFD_OUTROS_ESTADOS;
                        }
                    }

                    for (int pagina1based = doc.paginaInicial(); pagina1based <= doc.paginaFinal(); pagina1based++) {
                        int idx = pagina1based - 1;
                        if (idx < 0 || idx >= total) {
                            continue;
                        }
                        categoriaFinal[idx] = categoriaEfetiva;
                        metodoFinal[idx] = continuaDocumentoAnterior && pagina1based == doc.paginaInicial()
                                ? MetodoClassificacao.REGRA_BLOCO_TFD
                                : MetodoClassificacao.GEMINI_CONTEXTO;
                    }

                    paginaFinalAbertaAnterior = doc.paginaFinal();
                    categoriaAbertaAnterior = categoriaEfetiva;
                    rsConfirmadoAnterior = categoriaEfetiva == Categoria.TFD_RS && rsConfirmadoAqui;
                }

                for (int paginaNr : resultado.paginasNaoResolvidas()) {
                    int idx = paginaNr - 1;
                    if (idx < 0 || idx >= total) {
                        continue;
                    }
                    if (idx > 0 && categoriaFinal[idx - 1] != null) {
                        // Reparo simples: buraco de 1-2 paginas no meio de um bloco ja identificado
                        // provavelmente e continuacao dele (regra do negocio: nao perder pagina) -
                        // só rotula como "regra de bloco TFD" quando a categoria herdada realmente
                        // for de TFD, senão o relatório mostraria esse rótulo numa página que não
                        // tem nada a ver com TFD.
                        categoriaFinal[idx] = categoriaFinal[idx - 1];
                        metodoFinal[idx] = CATEGORIAS_TFD.contains(categoriaFinal[idx - 1])
                                ? MetodoClassificacao.REGRA_BLOCO_TFD
                                : MetodoClassificacao.GEMINI_CONTEXTO;
                    } else {
                        categoriaFinal[idx] = normalizados.get(idx).isBlank()
                                ? Categoria.OUTROS
                                : palavraChave.classificar(normalizados.get(idx));
                        metodoFinal[idx] = normalizados.get(idx).isBlank() ? MetodoClassificacao.SEM_TEXTO
                                : MetodoClassificacao.PALAVRA_CHAVE;
                    }
                }
            }

            inicioJanela += tamanhoJanela;
        }

        List<PaginaClassificada> saida = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            Categoria categoria = categoriaFinal[i] != null ? categoriaFinal[i] : Categoria.OUTROS;
            MetodoClassificacao metodo = metodoFinal[i] != null ? metodoFinal[i] : MetodoClassificacao.PALAVRA_CHAVE;
            saida.add(new PaginaClassificada(categoria, metodo, normalizados.get(i), null));
        }
        return saida;
    }

    /**
     * A categoria TFD_RS é um requisito de negócio crítico demais para confiar só no julgamento do Gemini sobre o
     * documento inteiro — confere em Java se pelo menos uma página do documento realmente bate nos termos
     * exclusivos do RS (mesma condição usada no fallback por palavra-chave).
     */
    private boolean algumaPaginaConfirmaRs(List<String> normalizados, int paginaInicial, int paginaFinal) {
        for (int pagina = paginaInicial; pagina <= paginaFinal; pagina++) {
            if (pagina < 1 || pagina > normalizados.size()) {
                continue;
            }
            String texto = normalizados.get(pagina - 1);
            if (palavraChave.contemAlgumaPalavra(texto, config.tfdRsMarcador())
                    && palavraChave.contemAlgumaPalavra(texto, config.tfdTermoGenerico())) {
                return true;
            }
        }
        return false;
    }

    private String truncar(String texto, int maxCaracteres) {
        if (texto == null) {
            return "";
        }
        String t = texto.trim();
        return t.length() <= maxCaracteres ? t : t.substring(0, maxCaracteres);
    }
}

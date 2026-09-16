package com.rafael.pdfsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.rafael.pdfsplitter.gemini.DocumentoDetectado;
import com.rafael.pdfsplitter.gemini.GeminiClassificadorService;
import com.rafael.pdfsplitter.gemini.ResultadoAgrupamento;

class AgrupadorContextualServiceTest {

    private static final List<String> RS_MARCADOR = List.of("rio grande do sul");
    private static final List<String> TFD_GENERICO = List.of("tfd");

    private final ClassificadorConfig config = new ClassificadorConfig() {
        @Override public List<String> tfdRsMarcador() { return RS_MARCADOR; }
        @Override public List<String> tfdTermoGenerico() { return TFD_GENERICO; }
        @Override public List<String> protocoloEncaminhamento() { return List.of(); }
        @Override public List<String> exames() { return List.of("exame"); }
        @Override public List<String> documentos() { return List.of("rg"); }
        @Override public int tfdRsPaginasPorBloco() { return 3; }
        @Override public List<String> tfdCabecalhoNovoDocumento() { return List.of(); }
        @Override public boolean tfdExigirCabecalhoParaQuebrarBloco() { return true; }
        @Override public String modo() { return "CONTEXTO"; }
        @Override public int contextoPaginasPorJanela() { return 10; }
        @Override public int contextoPaginasDeContexto() { return 4; }
        @Override public int contextoMaxCaracteresPorPagina() { return 2500; }
        @Override public int tamanhoMaximoArquivoMb() { return 10; }
    };

    private final ClassificadorPalavraChaveService palavraChave = new ClassificadorPalavraChaveService(config);

    /** Duplo de teste que devolve um {@link ResultadoAgrupamento} fixo por janela, sem chamar a API de verdade. */
    private static final class GeminiFalso extends GeminiClassificadorService {
        private final List<ResultadoAgrupamento> respostasPorJanela;
        private int chamada = 0;

        GeminiFalso(List<ResultadoAgrupamento> respostasPorJanela) {
            super(null);
            this.respostasPorJanela = respostasPorJanela;
        }

        @Override
        public boolean disponivel() {
            return true;
        }

        @Override
        public ResultadoAgrupamento agrupar(List<String> textosContexto, int primeiraPaginaContexto,
                List<String> textosDecisao, int primeiraPaginaDecisao) {
            return respostasPorJanela.get(chamada++);
        }
    }

    private AgrupadorContextualService criar(GeminiClassificadorService gemini) {
        return new AgrupadorContextualService(config, gemini, palavraChave);
    }

    @Test
    void semGeminiDisponivelUsaFallbackPorPalavraChaveEmTodasAsPaginas() {
        GeminiClassificadorService semChave = new GeminiClassificadorService(null) {
            @Override public boolean disponivel() { return false; }
        };
        AgrupadorContextualService agrupador = criar(semChave);

        List<PaginaClassificada> resultado = agrupador.classificar(List.of("cópia do RG do paciente", "laudo de exame"));

        assertEquals(Categoria.DOCUMENTOS, resultado.get(0).getCategoria());
        assertEquals(MetodoClassificacao.PALAVRA_CHAVE, resultado.get(0).getMetodo());
        assertEquals(Categoria.EXAMES, resultado.get(1).getCategoria());
        assertEquals(MetodoClassificacao.PALAVRA_CHAVE, resultado.get(1).getMetodo());
    }

    @Test
    void tfdRsSemMarcadorNoTextoERebaixadoParaOutrosEstados() {
        // O Gemini diz TFD_RS, mas nenhuma página do documento contém o marcador do RS -
        // guard de negócio deve rebaixar para TFD_OUTROS_ESTADOS (nunca confia só no modelo).
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_RS)), java.util.Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of("pedido de tfd sem indicar o estado"));

        assertEquals(Categoria.TFD_OUTROS_ESTADOS, resultado.get(0).getCategoria());
    }

    @Test
    void tfdRsComMarcadorNoTextoEConfirmado() {
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_RS)), java.util.Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of("pedido de tfd do estado do rio grande do sul"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
    }

    @Test
    void anexoDeOutroEstadoContinuaConfirmacaoDoDocumentoRsAnterior() {
        // Caso real que motivou o redesenho: a capa do RS (com marcador) é seguida por um
        // laudo médico anexo que não menciona o RS - como é continuação direta do MESMO
        // documento TFD_RS, deve herdar a confirmação em vez de ser rebaixado.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 2, Categoria.TFD_RS)),
                java.util.Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                "laudo médico do estado de origem do paciente, sem mencionar o rs"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
        assertEquals(Categoria.TFD_RS, resultado.get(1).getCategoria());
        assertEquals(MetodoClassificacao.REGRA_BLOCO_TFD, resultado.get(1).getMetodo());
    }

    @Test
    void tfdRsDeDocumentoNaoContinuoNaoHerdaConfirmacaoAnterior() {
        // Mesma categoria TFD_RS, mas NÃO é contínuo (página inicial != fim anterior + 1) -
        // não deve herdar a confirmação do documento anterior.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(3, 3, Categoria.TFD_RS)),
                java.util.Set.of(2));
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                "página não resolvida",
                "documento sem marcador do rs"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
        assertEquals(Categoria.TFD_OUTROS_ESTADOS, resultado.get(2).getCategoria());
    }

    @Test
    void janelaComCoberturaBaixaCaiInteiraNoFallback() {
        // Menos de 60% das páginas da janela resolvidas -> janela inteira cai no fallback,
        // mesmo as páginas que o Gemini resolveu.
        ResultadoAgrupamento janelaFraca = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_RS)), java.util.Set.of(2, 3));
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janelaFraca)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "pedido de tfd do rio grande do sul", "laudo de exame", "cópia do rg"));

        assertEquals(MetodoClassificacao.PALAVRA_CHAVE, resultado.get(0).getMetodo());
        assertEquals(MetodoClassificacao.PALAVRA_CHAVE, resultado.get(1).getMetodo());
        assertEquals(MetodoClassificacao.PALAVRA_CHAVE, resultado.get(2).getMetodo());
    }

    @Test
    void paginaSemTextoVaiParaSemTextoNoFallback() {
        GeminiClassificadorService semChave = new GeminiClassificadorService(null) {
            @Override public boolean disponivel() { return false; }
        };
        AgrupadorContextualService agrupador = criar(semChave);

        List<PaginaClassificada> resultado = agrupador.classificar(List.of("   "));

        assertEquals(Categoria.OUTROS, resultado.get(0).getCategoria());
        assertEquals(MetodoClassificacao.SEM_TEXTO, resultado.get(0).getMetodo());
    }
}

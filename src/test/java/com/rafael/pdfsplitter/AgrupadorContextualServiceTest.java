package com.rafael.pdfsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.rafael.pdfsplitter.gemini.DocumentoDetectado;
import com.rafael.pdfsplitter.gemini.GeminiClassificadorService;
import com.rafael.pdfsplitter.gemini.ResultadoAgrupamento;

class AgrupadorContextualServiceTest {

    private static final List<String> RS_MARCADOR = List.of("rio grande do sul");
    private static final List<String> TFD_GENERICO = List.of("tfd");
    private static final List<String> CABECALHO_NOVO_DOCUMENTO = List.of("secretaria de saude de outro estado - capa");
    private static final List<String> CARIMBO_PROTOCOLO = List.of("inserido ao protocolo", "download realizado",
            "a autenticidade deste documento pode ser validada", "eprotocolo", "validardocumento");

    /** Texto curto, bem abaixo do limiar de conteúdo útil - simula um anexo escaneado sem OCR. */
    private static final String CARIMBO_POBRE = "Inserido ao protocolo 26.255.858-4 por: Tais Candiotto de Lima. A "
            + "autenticidade deste documento pode ser validada no endereço: eprotocolo.pr.gov.br/validardocumento";

    private final ClassificadorConfig config = new ClassificadorConfig() {
        @Override public List<String> tfdRsMarcador() { return RS_MARCADOR; }
        @Override public List<String> tfdTermoGenerico() { return TFD_GENERICO; }
        @Override public List<String> protocoloEncaminhamento() { return List.of(); }
        @Override public List<String> exames() { return List.of("exame"); }
        @Override public List<String> documentos() { return List.of("rg"); }
        @Override public int tfdRsPaginasPorBloco() { return 3; }
        @Override public List<String> tfdCabecalhoNovoDocumento() { return CABECALHO_NOVO_DOCUMENTO; }
        @Override public boolean tfdExigirCabecalhoParaQuebrarBloco() { return true; }
        @Override public String modo() { return "CONTEXTO"; }
        @Override public int contextoPaginasPorJanela() { return 10; }
        @Override public int contextoPaginasDeContexto() { return 4; }
        @Override public int contextoMaxCaracteresPorPagina() { return 2500; }
        @Override public int tamanhoMaximoArquivoMb() { return 10; }
        @Override public List<String> contextoCarimboProtocoloPadroes() { return CARIMBO_PROTOCOLO; }
        @Override public int contextoMinCaracteresConteudoUtil() { return 120; }
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
        // O Gemini diz TFD_RS, mas nenhuma página do documento contém o marcador do RS, e a página
        // tem conteúdo real (não é "pobre") - guard de negócio deve rebaixar para TFD_OUTROS_ESTADOS
        // (nunca confia só no modelo, mas também não inventa OUTROS quando há conteúdo genuíno).
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_RS)), Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(
                List.of("pedido de tfd sem indicar o estado, com queixa clinica detalhada do paciente e historico "
                        + "medico completo relatando dor cronica e dificuldade de locomocao ha varios meses"));

        assertEquals(Categoria.TFD_OUTROS_ESTADOS, resultado.get(0).getCategoria());
    }

    @Test
    void tfdRsSemMarcadorEPaginaPobreSemBundleAbertoViraOutrosEmVezDeOutrosEstados() {
        // P2b do relatorio do Opus: uma pagina praticamente vazia (so carimbo), sem bundle RS
        // aberto pra herdar, nao deve fabricar TFD_OUTROS_ESTADOS do nada - vira OUTROS.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_RS)), Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(CARIMBO_POBRE));

        assertEquals(Categoria.OUTROS, resultado.get(0).getCategoria());
    }

    @Test
    void tfdRsComMarcadorNoTextoEConfirmado() {
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_RS)), Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of("pedido de tfd do estado do rio grande do sul"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
    }

    @Test
    void anexoDeOutroEstadoContinuaConfirmacaoDoDocumentoRsAnterior() {
        // Caso real que motivou o redesenho: a capa do RS (com marcador) é seguida por um
        // laudo médico anexo que não menciona o RS - como é continuação direta do MESMO
        // documento TFD_RS (mesma categoria bruta), deve herdar a confirmação em vez de ser rebaixado.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 2, Categoria.TFD_RS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                "laudo médico do estado de origem do paciente, sem mencionar o rs, com queixa clinica detalhada"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
        assertEquals(Categoria.TFD_RS, resultado.get(1).getCategoria());
        assertEquals(MetodoClassificacao.REGRA_BLOCO_TFD, resultado.get(1).getMetodo());
    }

    @Test
    void anexoQuaseVazioComCategoriaBrutaDiferenteEAbsorvidoNoBundleRs() {
        // O BUG REAL relatado em 2026-09-17: paginas de anexo escaneado sem OCR (so o carimbo do
        // protocolo, sem nenhum conteudo real) vieram do Gemini com uma categoria BRUTA diferente
        // (TFD_OUTROS_ESTADOS) da capa RS aberta - antes da correcao, isso virava um documento novo
        // errado; agora deve ser absorvido no bundle TFD_RS aberto, independente da categoria bruta.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 4, Categoria.TFD_OUTROS_ESTADOS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                CARIMBO_POBRE, CARIMBO_POBRE, CARIMBO_POBRE));

        assertEquals(Categoria.TFD_RS, resultado.get(1).getCategoria());
        assertEquals(Categoria.TFD_RS, resultado.get(2).getCategoria());
        assertEquals(Categoria.TFD_RS, resultado.get(3).getCategoria());
        assertEquals(MetodoClassificacao.REGRA_BLOCO_TFD, resultado.get(1).getMetodo());
    }

    @Test
    void anexoQuaseVazioComCabecalhoDeNovoDocumentoNaoEAbsorvido() {
        // Guard anti-vazamento: mesmo pobre e contiguo a um bundle RS aberto, uma pagina que bate
        // no cabecalho de "capa de novo pedido" nunca deve ser absorvida - e realmente um documento novo.
        String paginaPobreComCabecalho = palavraChave.normalizar(CARIMBO_POBRE) + " secretaria de saude de outro estado - capa";
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 2, Categoria.TFD_OUTROS_ESTADOS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                paginaPobreComCabecalho));

        assertEquals(Categoria.TFD_OUTROS_ESTADOS, resultado.get(1).getCategoria());
    }

    @Test
    void buracoNoFimDaJanelaNaoQuebraACosturaComAJanelaSeguinte() {
        // Falha #3 do relatorio do Opus: pagina nao resolvida no fim de uma janela nao pode "esquecer"
        // o estado do bundle aberto para a janela seguinte. Janela 1 = paginas 1-10 (pagina 10 nao
        // resolvida); janela 2 = pagina 11, TFD_RS bruto sem marcador proprio -> deve herdar a
        // confirmacao do bundle RS aberto na pagina 1, atravessando o buraco da pagina 10.
        List<String> paginas = new java.util.ArrayList<>();
        paginas.add("capa do estado do rio grande do sul - pedido de tfd");
        for (int i = 2; i <= 9; i++) {
            paginas.add(CARIMBO_POBRE);
        }
        paginas.add(CARIMBO_POBRE); // pagina 10, sera a nao resolvida
        paginas.add("documento sem marcador do rs, so com queixa clinica detalhada do paciente e historico medico");

        ResultadoAgrupamento janela1 = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 9, Categoria.TFD_RS)), Set.of(10));
        ResultadoAgrupamento janela2 = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(11, 11, Categoria.TFD_RS)), Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela1, janela2)));

        List<PaginaClassificada> resultado = agrupador.classificar(paginas);

        assertEquals(Categoria.TFD_RS, resultado.get(10).getCategoria()); // pagina 11, indice 10
    }

    @Test
    void rebaixamentoNaoContaminaAutoconfirmacaoDeTrechoSeguinte() {
        // Falha #2 do relatorio: a costura compara categoria BRUTA com BRUTA, nunca com a EFETIVA (ja
        // rebaixada) - um trecho rebaixado (efetivo != bruto) nao pode "vazar" seu efetivo pro proximo
        // trecho via comparacao incorreta. Aqui dois trechos seguidos sao rebaixados (sem marcador, sem
        // bundle aberto pra herdar), e um terceiro, com marcador proprio, deve se autoconfirmar como
        // TFD_RS normalmente - o histórico de rebaixamento anterior não pode impedir isso.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS), // sem marcador, sem bundle -> rebaixa
                        new DocumentoDetectado(2, 2, Categoria.TFD_RS), // idem -> rebaixa de novo
                        new DocumentoDetectado(3, 3, Categoria.TFD_RS)), // tem marcador proprio -> autoconfirma
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "trecho generico de tfd sem nenhum marcador de estado, com bastante conteudo clinico detalhado "
                        + "descrevendo o quadro do paciente, historico de doencas e justificativa do procedimento solicitado",
                "outro trecho generico de tfd tambem sem nenhum marcador de estado, com conteudo clinico detalhado "
                        + "descrevendo evolucao do quadro, exames complementares realizados e conduta medica adotada",
                "novo pedido de tfd que menciona claramente o estado do rio grande do sul"));

        assertEquals(Categoria.TFD_OUTROS_ESTADOS, resultado.get(0).getCategoria());
        assertEquals(Categoria.TFD_OUTROS_ESTADOS, resultado.get(1).getCategoria());
        assertEquals(Categoria.TFD_RS, resultado.get(2).getCategoria());
    }

    @Test
    void documentoComConteudoRealDeCategoriaDiferenteNaoEAbsorvidoNoBundle() {
        // Anti-regressao da regra de negocio: um documento adjacente com CONTEUDO REAL (nao pobre) de
        // categoria diferente e um documento genuinamente novo, nao um anexo do bundle anterior.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 2, Categoria.EXAMES)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                "resultado completo de exame de sangue hemograma com todos os valores de referencia detalhados, incluindo contagem de hemacias, leucocitos, plaquetas e demais indices bioquimicos do paciente"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
        assertEquals(Categoria.EXAMES, resultado.get(1).getCategoria());
    }

    @Test
    void bundleDeOutrosEstadosNaoEPromovidoParaRsPorPaginasPobresSeguintes() {
        // Assimetria de negocio documentada: paginas pobres absorvidas herdam a categoria EFETIVA do
        // bundle aberto - se o bundle aberto e TFD_OUTROS_ESTADOS (nunca confirmado como RS), as
        // paginas pobres seguintes continuam TFD_OUTROS_ESTADOS, nunca "sobem" para TFD_RS.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_OUTROS_ESTADOS),
                        new DocumentoDetectado(2, 2, Categoria.TFD_OUTROS_ESTADOS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "pedido de tfd generico sem nenhum marcador de estado, com bastante conteudo clinico detalhado",
                CARIMBO_POBRE));

        assertEquals(Categoria.TFD_OUTROS_ESTADOS, resultado.get(0).getCategoria());
        assertEquals(Categoria.TFD_OUTROS_ESTADOS, resultado.get(1).getCategoria());
    }

    @Test
    void tfdRsDeDocumentoNaoContinuoNaoHerdaConfirmacaoAnterior() {
        // Nao continuo de verdade: um documento EXAMES com conteudo real quebra a cadeia entre dois
        // trechos TFD_RS - o segundo trecho, sem marcador proprio, nao deve herdar a confirmacao do
        // primeiro (a cadeia de continuidade foi interrompida por um documento genuinamente diferente).
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 2, Categoria.EXAMES),
                        new DocumentoDetectado(3, 3, Categoria.TFD_RS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                "resultado completo de exame de sangue hemograma com todos os valores de referencia detalhados, incluindo contagem de hemacias, leucocitos, plaquetas e demais indices bioquimicos do paciente",
                "documento sem marcador do rs, so com queixa clinica detalhada do paciente, historico medico "
                        + "completo, sinais e sintomas relatados e justificativa clinica do procedimento solicitado"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
        assertEquals(Categoria.TFD_OUTROS_ESTADOS, resultado.get(2).getCategoria());
    }

    @Test
    void janelaComCoberturaBaixaCaiInteiraNoFallback() {
        // Menos de 60% das páginas da janela resolvidas -> janela inteira cai no fallback,
        // mesmo as páginas que o Gemini resolveu.
        ResultadoAgrupamento janelaFraca = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_RS)), Set.of(2, 3));
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

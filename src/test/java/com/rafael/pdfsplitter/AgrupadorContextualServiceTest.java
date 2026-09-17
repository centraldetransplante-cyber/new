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
        @Override public List<String> identificacaoPessoalInequivoca() { return List.of("rg", "cpf"); }
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
    void tfdOutrosEstadosDiretoDoGeminiSemConteudoRealESemBundleViraOutros() {
        // Achado do code-review: o guard so cobria o caminho de REBAIXAMENTO (Gemini disse TFD_RS,
        // Java rebaixou). Se o Gemini ja devolve TFD_OUTROS_ESTADOS direto numa pagina pobre sem
        // nenhum bundle TFD aberto pra herdar, o mesmo raciocinio se aplica - nao fabricar a
        // categoria do nada.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_OUTROS_ESTADOS)), Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(CARIMBO_POBRE));

        assertEquals(Categoria.OUTROS, resultado.get(0).getCategoria());
    }

    @Test
    void duasPaginasPobresSeguidasSemBundleNaoCascateiamParaOutrosEstados() {
        // Achado do code-review: apos uma pagina TFD_RS ser rebaixada pra OUTROS (pobre, sem bundle),
        // o bundle aberto (baseado na categoria EFETIVA) se fecha - uma segunda pagina pobre contigua,
        // que o Gemini rotula TFD_OUTROS_ESTADOS, nao pode "vazar" essa categoria so porque a anterior
        // tambem era pobre; ambas devem ficar OUTROS de forma consistente.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 2, Categoria.TFD_OUTROS_ESTADOS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(CARIMBO_POBRE, CARIMBO_POBRE));

        assertEquals(Categoria.OUTROS, resultado.get(0).getCategoria());
        assertEquals(Categoria.OUTROS, resultado.get(1).getCategoria());
    }

    @Test
    void documentoPessoalCurtoComPalavraChaveNaoEAbsorvidoNoBundleAbertoMesmoSendoPobre() {
        // Achado do code-review: um RG/CPF anexado logo apos a capa RS costuma ter pouco texto
        // extraivel (curto o bastante pra contar como "pobre" pelo limiar de caracteres), mas isso
        // NAO significa que deva ser engolido pelo bundle de TFD - a palavra-chave da propria
        // categoria (aqui "rg") e sinal forte o bastante pra nao absorver.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 2, Categoria.DOCUMENTOS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                "copia do rg do paciente"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
        assertEquals(Categoria.DOCUMENTOS, resultado.get(1).getCategoria());
    }

    @Test
    void tfdRsSemMarcadorEComPalavraChavePropriaRoteiaParaCategoriaDetectadaEmVezDeOutros() {
        // Achado do code-review: o Gemini rotulou (errado) uma pagina curta de RG como TFD_RS - sem
        // marcador do RS, sem bundle pra herdar, mas com uma palavra-chave de categoria propria
        // ("rg"). Deve rotear pra DOCUMENTOS (a categoria real), nao cair num OUTROS generico so
        // porque o texto e curto o bastante pra contar como "pobre".
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_RS)), Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of("copia do rg do paciente"));

        assertEquals(Categoria.DOCUMENTOS, resultado.get(0).getCategoria());
        // REGRA_JAVA, não PALAVRA_CHAVE: o Gemini respondeu normalmente (não falhou) - foi uma regra de negócio em
        // Java que rebaixou a categoria dele. PALAVRA_CHAVE deve ficar reservado pra quando a IA de fato falhou,
        // senão o resumo mostrado ao usuário ("N páginas não puderam ser classificadas pela IA") conta casos onde
        // a IA funcionou perfeitamente.
        assertEquals(MetodoClassificacao.REGRA_JAVA, resultado.get(0).getMetodo());
    }

    @Test
    void paginaComPalavraChaveInterrompeBundleTfdSemArrastarAsDemaisPaginasJunto() {
        // Achado da auditoria (Opus, 2026-09-17): quando o Gemini agrupa VÁRIAS páginas pobres como um único
        // documento TFD_RS (2-4) e só UMA delas (a 3, um RG anexado) bate uma palavra-chave própria, o
        // reroteamento antigo arrancava o DOCUMENTO INTEIRO (2, 3 e 4) do bundle RS aberto pela página 1, jogando
        // as páginas 2 e 4 - que não tinham nenhum sinal próprio e são anexos legítimos do mesmo pedido - junto
        // com a 3 em DOCUMENTOS. O correto é reroteear só a página 3; as páginas 2 e 4 devem continuar TFD_RS,
        // herdando o bundle confirmado pela capa (página 1).
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 4, Categoria.TFD_RS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                CARIMBO_POBRE,
                CARIMBO_POBRE + " rg",
                CARIMBO_POBRE));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());

        assertEquals(Categoria.TFD_RS, resultado.get(1).getCategoria());
        assertEquals(MetodoClassificacao.REGRA_BLOCO_TFD, resultado.get(1).getMetodo());

        assertEquals(Categoria.DOCUMENTOS, resultado.get(2).getCategoria());
        assertEquals(MetodoClassificacao.REGRA_JAVA, resultado.get(2).getMetodo());

        // A página seguinte à interrupção precisa continuar enxergando o bundle RS aberto, não "esquecê-lo" por
        // causa da página 3 - é exatamente esse esquecimento que o bug antigo causava.
        assertEquals(Categoria.TFD_RS, resultado.get(3).getCategoria());
        assertEquals(MetodoClassificacao.REGRA_BLOCO_TFD, resultado.get(3).getMetodo());
    }

    @Test
    void tfdComConteudoRicoQueMencionaPalavraIncidentalNaoEDesviadoDaCategoria() {
        // Achado do code-review: o reroteamento por palavra-chave propria (e o rebaixamento pra
        // OUTROS) so podem valer pra pagina "pobre" de verdade - um documento TFD longo, com
        // conteudo clinico real, que por acaso menciona uma palavra de outra lista (aqui "certidao",
        // que esta em config.documentos()) no meio do texto corrido NAO pode ser arrancado do bundle
        // por isso. Sem marcador do RS e sem bundle aberto, deve permanecer TFD_OUTROS_ESTADOS.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_RS)), Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "pedido de tfd sem indicar o estado, com queixa clinica detalhada relatando necessidade de "
                        + "juntar certidao de nascimento ao processo, alem de historico medico completo do paciente"));

        assertEquals(Categoria.TFD_OUTROS_ESTADOS, resultado.get(0).getCategoria());
    }

    @Test
    void paginaPobreComPalavraChavePropriaERoteadaMesmoComBundleTfdAberto() {
        // Achado do code-review: o reroteamento pra categoria propria so disparava quando NAO havia
        // bundle TFD aberto - com bundle aberto, a pagina ficava presa na categoria bruta errada do
        // Gemini (TFD_OUTROS_ESTADOS) em vez de ser reconhecida como o RG que realmente e.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 2, Categoria.TFD_OUTROS_ESTADOS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                "copia do rg do paciente"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
        assertEquals(Categoria.DOCUMENTOS, resultado.get(1).getCategoria());
    }

    @Test
    void laudoMedicoPobreContiguoAoBundleEAbsorvidoMesmoContendoPalavraDeExames() {
        // Achado do code-review, o mais critico dos tres: "laudo" esta na lista de config.exames(), e um
        // LAUDO MEDICO anexo ao pedido de TFD/RS (regra de negocio #2) e exatamente o cenario que a
        // absorcao por pagina pobre existe pra resolver. Se o guard de "categoria propria" considerasse
        // EXAMES, um laudo mal-OCRizado (poucas letras, mas com a palavra "laudo" sobrevivendo) seria
        // arrancado do bundle RS e mandado pra exames.pdf - reproduzindo o bug original desta arquitetura
        // por um caminho novo. Precisa continuar sendo absorvido no bundle TFD_RS.
        String laudoPobreComPalavraExame = "laudo " + CARIMBO_POBRE;
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 2, Categoria.TFD_OUTROS_ESTADOS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                laudoPobreComPalavraExame));

        assertEquals(Categoria.TFD_RS, resultado.get(1).getCategoria());
        assertEquals(MetodoClassificacao.REGRA_BLOCO_TFD, resultado.get(1).getMetodo());
    }

    @Test
    void rgComMesmaCategoriaBrutaDeDocumentoRsConfirmadoNaoHerdaTfdRs() {
        // Achado do code-review: se o Gemini repete TFD_RS (a mesma categoria bruta do documento
        // anterior confirmado) por engano pra um RG anexado, a heranca de confirmacao nao pode vencer o
        // sinal de palavra-chave propria - um RG nunca deveria virar TFD_RS, seja qual for a categoria
        // bruta que o Gemini deu a ele.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(
                        new DocumentoDetectado(1, 1, Categoria.TFD_RS),
                        new DocumentoDetectado(2, 2, Categoria.TFD_RS)),
                Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(List.of(
                "capa do estado do rio grande do sul - pedido de tfd",
                "copia do rg do paciente"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
        assertEquals(Categoria.DOCUMENTOS, resultado.get(1).getCategoria());
    }

    @Test
    void autoconfirmacaoDeRsVenceSinalDeCategoriaPropriaIncidental() {
        // Achado do code-review: uma capa RS curta que por acaso menciona uma palavra de
        // identificacao-pessoal-inequivoca ("rg") no meio do texto NAO pode ser desviada pra
        // DOCUMENTOS so por isso - o marcador de RS de verdade na propria pagina sempre vence.
        ResultadoAgrupamento janela = new ResultadoAgrupamento(
                List.of(new DocumentoDetectado(1, 1, Categoria.TFD_RS)), Set.of());
        AgrupadorContextualService agrupador = criar(new GeminiFalso(List.of(janela)));

        List<PaginaClassificada> resultado = agrupador.classificar(
                List.of("pedido de tfd do estado do rio grande do sul, anexar copia do rg do paciente"));

        assertEquals(Categoria.TFD_RS, resultado.get(0).getCategoria());
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
                        + "descrevendo evolucao do quadro, sinais vitais observados e conduta medica adotada pela equipe",
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
                "pedido de tfd generico sem nenhum marcador de estado, com bastante conteudo clinico detalhado "
                        + "descrevendo o quadro do paciente, historico de doencas e justificativa do procedimento solicitado",
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

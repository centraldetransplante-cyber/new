package com.rafael.pdfsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ClassificadorPalavraChaveServiceTest {

    private static final List<String> RS_MARCADOR = List.of("rio grande do sul", "central estadual de transplantes");
    private static final List<String> TFD_GENERICO = List.of("tfd", "tratamento fora de domicilio");
    private static final List<String> PROTOCOLO = List.of("protocolo de encaminhamento");
    private static final List<String> EXAMES = List.of("exame", "laudo");
    private static final List<String> DOCUMENTOS = List.of("rg", "cpf", "certidao");
    private static final List<String> CARIMBO_PROTOCOLO = List.of("inserido ao protocolo", "download realizado",
            "a autenticidade deste documento pode ser validada", "eprotocolo", "validardocumento");

    private final ClassificadorConfig config = new ClassificadorConfig() {
        @Override public List<String> tfdRsMarcador() { return RS_MARCADOR; }
        @Override public List<String> tfdTermoGenerico() { return TFD_GENERICO; }
        @Override public List<String> protocoloEncaminhamento() { return PROTOCOLO; }
        @Override public List<String> exames() { return EXAMES; }
        @Override public List<String> documentos() { return DOCUMENTOS; }
        @Override public int tfdRsPaginasPorBloco() { return 3; }
        @Override public List<String> tfdCabecalhoNovoDocumento() { return List.of(); }
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

    private final ClassificadorPalavraChaveService classificador = new ClassificadorPalavraChaveService(config);

    @Test
    void classificaTfdRsQuandoTemMarcadorRsETermoGenerico() {
        String texto = classificador.normalizar("Pedido de TFD - Estado do Rio Grande do Sul");
        assertEquals(Categoria.TFD_RS, classificador.classificar(texto));
    }

    @Test
    void marcadorRsSozinhoNaoClassificaComoTfd() {
        // "central estadual de transplantes" aparece em ofícios que não são sobre TFD -
        // sem o termo genérico de TFD junto, não deve virar TFD_RS (evita falso positivo).
        String texto = classificador.normalizar("Ofício da Central Estadual de Transplantes sobre outro assunto");
        assertEquals(Categoria.OUTROS, classificador.classificar(texto));
    }

    @Test
    void termoGenericoSemMarcadorRsVaiParaOutrosEstados() {
        String texto = classificador.normalizar("Solicitação de tratamento fora de domicílio");
        assertEquals(Categoria.TFD_OUTROS_ESTADOS, classificador.classificar(texto));
    }

    @Test
    void classificaExamesEDocumentosPelaOrdemDePrioridade() {
        assertEquals(Categoria.PROTOCOLO_ENCAMINHAMENTO,
                classificador.classificar(classificador.normalizar("Protocolo de encaminhamento entre unidades")));
        assertEquals(Categoria.EXAMES, classificador.classificar(classificador.normalizar("Laudo de exame de sangue")));
        assertEquals(Categoria.DOCUMENTOS, classificador.classificar(classificador.normalizar("Cópia do RG do paciente")));
        assertEquals(Categoria.OUTROS, classificador.classificar(classificador.normalizar("texto sem nenhuma palavra-chave")));
    }

    @Test
    void palavraCurtaUsaBordaDePalavraParaNaoBaterDentroDeOutraPalavra() {
        // "rg" não deve bater dentro de "urgente", "cirurgia" etc.
        String semRg = classificador.normalizar("Paciente com quadro de urgência para cirurgia");
        assertFalse(classificador.contemAlgumaPalavra(semRg, List.of("rg")));

        String comRg = classificador.normalizar("Anexo cópia do RG do paciente");
        assertTrue(classificador.contemAlgumaPalavra(comRg, List.of("rg")));
    }

    @Test
    void palavraLongaUsaContainsSimples() {
        String texto = classificador.normalizar("Segue certidão de nascimento anexa");
        assertTrue(classificador.contemAlgumaPalavra(texto, List.of("certidao")));
    }

    @Test
    void normalizarRemoveAcentosEDeixaMinusculo() {
        assertEquals("tratamento fora de domicilio", classificador.normalizar("Tratamento Fora de Domicílio"));
        assertEquals("", classificador.normalizar(null));
    }

    @Test
    void paginaSoComCarimboDeProtocoloEPobre() {
        // Caso real (2026-09-17): anexo escaneado sem OCR onde só o carimbo do sistema de
        // protocolo eletrônico foi capturado - tem texto (não é isBlank()), mas não diz nada
        // sobre a categoria do documento.
        String texto = classificador.normalizar(
                "Inserido ao protocolo 26.255.858-4 por: Tais Candiotto de Lima em: 15/07/2026 14:31. A "
                        + "autenticidade deste documento pode ser validada no endereço: "
                        + "https://www.eprotocolo.pr.gov.br/spiweb/validarDocumento com o código: "
                        + "2535f4424b1ef97163e5de9956afd911");
        assertTrue(classificador.paginaPobre(texto));
    }

    @Test
    void paginaComConteudoClinicoCurtoNaoEPobre() {
        String texto = classificador.normalizar(
                "Queixa principal: dor cronica lombar ha 6 meses, sem melhora com anti-inflamatorios, "
                        + "paciente refere piora progressiva e dificuldade de deambulacao");
        assertFalse(classificador.paginaPobre(texto));
    }

    @Test
    void textoVazioOuNuloEPobre() {
        assertTrue(classificador.paginaPobre(""));
        assertTrue(classificador.paginaPobre("   "));
        assertTrue(classificador.paginaPobre(null));
    }
}

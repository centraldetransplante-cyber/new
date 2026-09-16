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
}

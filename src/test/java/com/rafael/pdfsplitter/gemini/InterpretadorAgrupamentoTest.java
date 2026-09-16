package com.rafael.pdfsplitter.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.rafael.pdfsplitter.Categoria;

class InterpretadorAgrupamentoTest {

    @Test
    void agrupaPaginasConsecutivasDoMesmoDocumento() {
        String json = """
                [
                  {"pagina": 1, "documento": 1, "categoria": "TFD_RS"},
                  {"pagina": 2, "documento": 1, "categoria": "TFD_RS"},
                  {"pagina": 3, "documento": 1, "categoria": "TFD_RS"},
                  {"pagina": 4, "documento": 2, "categoria": "EXAMES"},
                  {"pagina": 5, "documento": 3, "categoria": "EXAMES"}
                ]
                """;
        ResultadoAgrupamento resultado = InterpretadorAgrupamento.interpretar(json, 1, 5);

        assertEquals(3, resultado.documentos().size());
        assertEquals(new DocumentoDetectado(1, 3, Categoria.TFD_RS), resultado.documentos().get(0));
        assertEquals(new DocumentoDetectado(4, 4, Categoria.EXAMES), resultado.documentos().get(1));
        assertEquals(new DocumentoDetectado(5, 5, Categoria.EXAMES), resultado.documentos().get(2));
        assertTrue(resultado.paginasNaoResolvidas().isEmpty());
    }

    @Test
    void docIdZeroEValido() {
        ResultadoAgrupamento resultado = InterpretadorAgrupamento.interpretar(
                "[{\"pagina\": 1, \"documento\": 0, \"categoria\": \"EXAMES\"}]", 1, 1);
        assertEquals(1, resultado.documentos().size());
        assertEquals(new DocumentoDetectado(1, 1, Categoria.EXAMES), resultado.documentos().get(0));
    }

    @Test
    void jsonInvalidoOuTruncadoTrataJanelaInteiraComoNaoResolvida() {
        // Simula uma resposta truncada por atingir o limite de maxOutputTokens.
        String jsonTruncado = "[{\"pagina\": 1, \"documento\": 1, \"categ";
        ResultadoAgrupamento resultado = InterpretadorAgrupamento.interpretar(jsonTruncado, 1, 2);

        assertTrue(resultado.documentos().isEmpty());
        assertEquals(2, resultado.paginasNaoResolvidas().size());
    }

    @Test
    void respostaVaziaTrataTodasAsPaginasComoNaoResolvidas() {
        ResultadoAgrupamento resultado = InterpretadorAgrupamento.interpretar("", 1, 3);
        assertTrue(resultado.documentos().isEmpty());
        assertEquals(3, resultado.paginasNaoResolvidas().size());

        ResultadoAgrupamento resultadoNulo = InterpretadorAgrupamento.interpretar(null, 1, 3);
        assertEquals(3, resultadoNulo.paginasNaoResolvidas().size());
    }

    @Test
    void paginaAusenteNoArrayVaiParaNaoResolvidas() {
        String json = """
                [
                  {"pagina": 1, "documento": 1, "categoria": "TFD_RS"},
                  {"pagina": 3, "documento": 2, "categoria": "EXAMES"}
                ]
                """;
        ResultadoAgrupamento resultado = InterpretadorAgrupamento.interpretar(json, 1, 3);
        assertEquals(2, resultado.documentos().size());
        assertEquals(1, resultado.paginasNaoResolvidas().size());
        assertTrue(resultado.paginasNaoResolvidas().contains(2));
    }

    @Test
    void categoriaNaoReconhecidaDescartaOItem() {
        ResultadoAgrupamento resultado = InterpretadorAgrupamento.interpretar(
                "[{\"pagina\": 1, \"documento\": 1, \"categoria\": \"CATEGORIA_INVENTADA\"}]", 1, 1);
        assertTrue(resultado.documentos().isEmpty());
        assertTrue(resultado.paginasNaoResolvidas().contains(1));
    }

    @Test
    void paginaDuplicadaMantemAPrimeiraOcorrencia() {
        String json = """
                [
                  {"pagina": 1, "documento": 1, "categoria": "TFD_RS"},
                  {"pagina": 1, "documento": 2, "categoria": "EXAMES"}
                ]
                """;
        ResultadoAgrupamento resultado = InterpretadorAgrupamento.interpretar(json, 1, 1);
        assertEquals(1, resultado.documentos().size());
        assertEquals(Categoria.TFD_RS, resultado.documentos().get(0).categoria());
    }

    @Test
    void paginaForaDoIntervaloDaJanelaEIgnorada() {
        ResultadoAgrupamento resultado = InterpretadorAgrupamento.interpretar(
                "[{\"pagina\": 99, \"documento\": 1, \"categoria\": \"EXAMES\"}]", 1, 5);
        assertTrue(resultado.documentos().isEmpty());
        assertTrue(resultado.paginasNaoResolvidas().contains(1));
    }
}

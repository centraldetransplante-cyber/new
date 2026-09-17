package com.rafael.pdfsplitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cobre só o novo caminho de merge de múltiplos PDFs de entrada — não precisa das dependências de classificação
 * (Gemini/palavra-chave), por isso instancia o serviço com elas nulas.
 */
class PdfSplitServiceMergeTest {

    private final PdfSplitService service = new PdfSplitService(null, null, null, null, null);

    @Test
    void mesclaVariosPdfsPreservandoOrdemDasPaginas(@TempDir Path tempDir) throws Exception {
        File pdfA = criarPdfComTexto(tempDir.resolve("a.pdf"), "pagina A1", "pagina A2");
        File pdfB = criarPdfComTexto(tempDir.resolve("b.pdf"), "pagina B1");

        try (var mesclado = service.mesclarPdfs(List.of(pdfA, pdfB))) {
            PDDocument documento = mesclado.documento();
            assertEquals(3, documento.getNumberOfPages());
            assertTrue(textoDaPagina(documento, 1).contains("pagina A1"));
            assertTrue(textoDaPagina(documento, 2).contains("pagina A2"));
            assertTrue(textoDaPagina(documento, 3).contains("pagina B1"));
        }
    }

    /**
     * Regressão do bug encontrado por revisão: um merge ingênuo que fecha cada PDF de origem logo após
     * importar suas páginas quebra a leitura/salvamento do documento final, porque {@code importPage} compartilha
     * o stream subjacente da página de origem. Este teste simula o uso real (extrair texto e SALVAR o documento
     * mesclado) para pegar exatamente esse tipo de regressão, não só ler o número de páginas.
     */
    @Test
    void documentoMescladoContinuaLegivelESalvavelComVariasOrigens(@TempDir Path tempDir) throws Exception {
        File pdfA = criarPdfComTexto(tempDir.resolve("a.pdf"), "pagina A1");
        File pdfB = criarPdfComTexto(tempDir.resolve("b.pdf"), "pagina B1", "pagina B2");
        File pdfC = criarPdfComTexto(tempDir.resolve("c.pdf"), "pagina C1");

        try (var mesclado = service.mesclarPdfs(List.of(pdfA, pdfB, pdfC))) {
            PDDocument documento = mesclado.documento();
            assertEquals(4, documento.getNumberOfPages());
            for (int pagina = 1; pagina <= 4; pagina++) {
                assertTrue(textoDaPagina(documento, pagina).length() > 0,
                        "página " + pagina + " deveria ter texto legível após o merge");
            }
            try (ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
                documento.save(saida);
                assertTrue(saida.size() > 0);
            }
        }
    }

    @Test
    void umUnicoArquivoNaoQuebra(@TempDir Path tempDir) throws Exception {
        File pdf = criarPdfComTexto(tempDir.resolve("unico.pdf"), "so uma pagina");
        try (var mesclado = service.mesclarPdfs(List.of(pdf))) {
            PDDocument documento = mesclado.documento();
            assertEquals(1, documento.getNumberOfPages());
            assertTrue(textoDaPagina(documento, 1).contains("so uma pagina"));
            assertTrue(mesclado.fontes().isEmpty());
        }
    }

    @Test
    void arquivoInvalidoNaListaIdentificaIndice(@TempDir Path tempDir) throws Exception {
        File pdfValido = criarPdfComTexto(tempDir.resolve("valido.pdf"), "ok");
        File invalido = tempDir.resolve("invalido.pdf").toFile();
        java.nio.file.Files.writeString(invalido.toPath(), "isso nao e um pdf");

        PdfInvalidoException ex = assertThrows(PdfInvalidoException.class,
                () -> service.mesclarPdfs(List.of(pdfValido, invalido)));
        assertTrue(ex.getMessage().contains("arquivo 2"));
    }

    private String textoDaPagina(PDDocument documento, int numeroPagina) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(numeroPagina);
        stripper.setEndPage(numeroPagina);
        return stripper.getText(documento);
    }

    private File criarPdfComTexto(Path destino, String... textosPorPagina) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (String texto : textosPorPagina) {
                PDPage pagina = new PDPage();
                doc.addPage(pagina);
                try (PDPageContentStream content = new PDPageContentStream(doc, pagina)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(texto);
                    content.endText();
                }
            }
            doc.save(destino.toFile());
        }
        return destino.toFile();
    }
}

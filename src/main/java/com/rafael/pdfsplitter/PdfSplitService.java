package com.rafael.pdfsplitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;

import com.rafael.pdfsplitter.gemini.GeminiClassificadorService;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PdfSplitService {

    private final ClassificadorConfig config;
    private final GeminiClassificadorService geminiService;

    public PdfSplitService(ClassificadorConfig config, GeminiClassificadorService geminiService) {
        this.config = config;
        this.geminiService = geminiService;
    }

    /**
     * Lê o PDF de entrada, classifica cada página e devolve um .zip (em memória)
     * com um único PDF por categoria (sem pastas), juntando todas as páginas
     * daquela categoria na ordem em que aparecem no documento original.
     */
    public byte[] separarEmZip(InputStream pdfInputStream) throws IOException {
        try (PDDocument origem = Loader.loadPDF(pdfInputStream.readAllBytes())) {
            List<Categoria> categoriaPorPagina = classificarPaginas(origem);
            estenderBlocosTfdRs(categoriaPorPagina);
            Map<Categoria, List<Integer>> paginasPorCategoria = agruparPorCategoria(categoriaPorPagina);
            return montarZip(origem, paginasPorCategoria);
        }
    }

    private List<Categoria> classificarPaginas(PDDocument documento) throws IOException {
        int totalPaginas = documento.getNumberOfPages();
        List<Categoria> resultado = new ArrayList<>(totalPaginas);
        PDFTextStripper stripper = new PDFTextStripper();

        for (int pagina = 1; pagina <= totalPaginas; pagina++) {
            stripper.setStartPage(pagina);
            stripper.setEndPage(pagina);
            String textoOriginal = stripper.getText(documento);
            resultado.add(classificarTexto(textoOriginal));
        }
        return resultado;
    }

    private Categoria classificarTexto(String textoOriginal) {
        Categoria viaIa = geminiService.classificar(textoOriginal);
        if (viaIa != null) {
            return viaIa;
        }
        return classificarPorPalavraChave(normalizar(textoOriginal));
    }

    private Categoria classificarPorPalavraChave(String textoNormalizado) {
        if (contemAlgumaPalavra(textoNormalizado, config.tfdRs())) {
            return Categoria.TFD_RS;
        }
        if (contemAlgumaPalavra(textoNormalizado, config.protocoloEncaminhamento())) {
            return Categoria.PROTOCOLO_ENCAMINHAMENTO;
        }
        if (contemAlgumaPalavra(textoNormalizado, config.exames())) {
            return Categoria.EXAMES;
        }
        if (contemAlgumaPalavra(textoNormalizado, config.documentos())) {
            return Categoria.DOCUMENTOS;
        }
        return Categoria.OUTROS;
    }

    private boolean contemAlgumaPalavra(String textoNormalizado, List<String> palavrasChave) {
        for (String palavra : palavrasChave) {
            if (textoNormalizado.contains(normalizar(palavra))) {
                return true;
            }
        }
        return false;
    }

    private String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return semAcento.toLowerCase();
    }

    /**
     * Normalmente só a 1ª página de um documento TFD/RS contém as palavras-chave;
     * as páginas seguintes do mesmo bloco costumam ser continuação sem texto
     * identificável. Ao detectar o gatilho, as próximas páginas até completar o
     * tamanho do bloco (config: classificador.tfd-rs-paginas-por-bloco) também
     * são marcadas como TFD/RS, mesmo sem palavra-chave própria.
     */
    private void estenderBlocosTfdRs(List<Categoria> categoriaPorPagina) {
        List<Categoria> original = new ArrayList<>(categoriaPorPagina);
        int tamanhoBloco = Math.max(1, config.tfdRsPaginasPorBloco());
        int totalPaginas = categoriaPorPagina.size();

        for (int i = 0; i < totalPaginas; i++) {
            if (original.get(i) != Categoria.TFD_RS) {
                continue;
            }
            int fimBloco = Math.min(totalPaginas, i + tamanhoBloco);
            for (int j = i + 1; j < fimBloco; j++) {
                categoriaPorPagina.set(j, Categoria.TFD_RS);
            }
        }
    }

    private Map<Categoria, List<Integer>> agruparPorCategoria(List<Categoria> categoriaPorPagina) {
        Map<Categoria, List<Integer>> paginasPorCategoria = new EnumMap<>(Categoria.class);
        for (int pagina = 0; pagina < categoriaPorPagina.size(); pagina++) {
            paginasPorCategoria
                    .computeIfAbsent(categoriaPorPagina.get(pagina), c -> new ArrayList<>())
                    .add(pagina);
        }
        return paginasPorCategoria;
    }

    private byte[] montarZip(PDDocument origem, Map<Categoria, List<Integer>> paginasPorCategoria) throws IOException {
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(zipBytes)) {
            for (Categoria categoria : Categoria.values()) {
                List<Integer> paginas = paginasPorCategoria.get(categoria);
                if (paginas == null || paginas.isEmpty()) {
                    continue;
                }

                byte[] pdfDaCategoria = extrairPaginas(origem, paginas);
                String nomeArquivo = categoria.getPastaSaida() + ".pdf";

                zip.putNextEntry(new ZipEntry(nomeArquivo));
                zip.write(pdfDaCategoria);
                zip.closeEntry();
            }
        }
        return zipBytes.toByteArray();
    }

    private byte[] extrairPaginas(PDDocument origem, List<Integer> paginas) throws IOException {
        try (PDDocument novo = new PDDocument();
                ByteArrayOutputStream saida = new ByteArrayOutputStream()) {
            for (int indicePagina : paginas) {
                PDPage pagina = origem.getPage(indicePagina);
                novo.importPage(pagina);
            }
            novo.save(saida);
            return saida.toByteArray();
        }
    }
}

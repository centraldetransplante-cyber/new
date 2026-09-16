package com.rafael.pdfsplitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafael.pdfsplitter.gemini.GeminiClassificadorService;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PdfSplitService {

    private final ClassificadorConfig config;
    private final GeminiClassificadorService geminiService;
    private final ObjectMapper objectMapper;

    public PdfSplitService(ClassificadorConfig config, GeminiClassificadorService geminiService, ObjectMapper objectMapper) {
        this.config = config;
        this.geminiService = geminiService;
        this.objectMapper = objectMapper;
    }

    /**
     * Lê o PDF de entrada, classifica cada página e devolve um .zip (em memória)
     * com um único PDF por categoria (sem pastas), juntando todas as páginas
     * daquela categoria na ordem em que aparecem no documento original, além de
     * um relatório em JSON dizendo se cada página foi classificada pelo Gemini,
     * por palavra-chave (fallback) ou pela regra de bloco do TFD/RS — esse
     * relatório não entra no zip, é devolvido à parte para a UI exibir.
     */
    public ResultadoSeparacao separar(InputStream pdfInputStream) throws IOException {
        try (PDDocument origem = Loader.loadPDF(pdfInputStream.readAllBytes())) {
            List<PaginaClassificada> classificacoes = classificarPaginas(origem);
            estenderBlocosTfdRs(classificacoes);
            Map<Categoria, List<Integer>> paginasPorCategoria = agruparPorCategoria(classificacoes);
            byte[] zip = montarZip(origem, paginasPorCategoria);
            String relatorioJson = new String(gerarRelatorioJson(classificacoes), java.nio.charset.StandardCharsets.UTF_8);
            return new ResultadoSeparacao(zip, relatorioJson);
        }
    }

    private List<PaginaClassificada> classificarPaginas(PDDocument documento) throws IOException {
        int totalPaginas = documento.getNumberOfPages();
        List<PaginaClassificada> resultado = new ArrayList<>(totalPaginas);
        PDFTextStripper stripper = new PDFTextStripper();

        for (int pagina = 1; pagina <= totalPaginas; pagina++) {
            stripper.setStartPage(pagina);
            stripper.setEndPage(pagina);
            String textoOriginal = stripper.getText(documento);
            resultado.add(classificarTexto(textoOriginal));
        }
        return resultado;
    }

    private PaginaClassificada classificarTexto(String textoOriginal) {
        Categoria viaIa = geminiService.classificar(textoOriginal);
        if (viaIa != null) {
            return new PaginaClassificada(viaIa, MetodoClassificacao.GEMINI);
        }
        Categoria viaPalavraChave = classificarPorPalavraChave(normalizar(textoOriginal));
        return new PaginaClassificada(viaPalavraChave, MetodoClassificacao.PALAVRA_CHAVE);
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
     * identificável (caem em OUTROS na classificação isolada). Ao detectar o
     * gatilho, as próximas páginas até completar o tamanho do bloco (config:
     * classificador.tfd-rs-paginas-por-bloco) também são marcadas como TFD/RS —
     * mas SOMENTE se elas não tiverem sido classificadas como algo próprio
     * (OUTROS). Uma página que já foi identificada como outra categoria (por
     * IA ou palavra-chave) significa que um novo documento começou ali, então
     * essa regra não a sobrescreve.
     */
    private void estenderBlocosTfdRs(List<PaginaClassificada> classificacoes) {
        List<Categoria> original = new ArrayList<>();
        for (PaginaClassificada p : classificacoes) {
            original.add(p.getCategoria());
        }

        int tamanhoBloco = Math.max(1, config.tfdRsPaginasPorBloco());
        int totalPaginas = classificacoes.size();

        for (int i = 0; i < totalPaginas; i++) {
            if (original.get(i) != Categoria.TFD_RS) {
                continue;
            }
            int fimBloco = Math.min(totalPaginas, i + tamanhoBloco);
            for (int j = i + 1; j < fimBloco; j++) {
                if (original.get(j) != Categoria.OUTROS) {
                    // Página já tem classificação própria (ex: começo de outro
                    // documento) — não faz parte do bloco TFD/RS, para de estender.
                    break;
                }
                classificacoes.get(j).setCategoria(Categoria.TFD_RS);
                classificacoes.get(j).setMetodo(MetodoClassificacao.REGRA_BLOCO_TFD_RS);
            }
        }
    }

    private Map<Categoria, List<Integer>> agruparPorCategoria(List<PaginaClassificada> classificacoes) {
        Map<Categoria, List<Integer>> paginasPorCategoria = new EnumMap<>(Categoria.class);
        for (int pagina = 0; pagina < classificacoes.size(); pagina++) {
            paginasPorCategoria
                    .computeIfAbsent(classificacoes.get(pagina).getCategoria(), c -> new ArrayList<>())
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

    private byte[] gerarRelatorioJson(List<PaginaClassificada> classificacoes) throws IOException {
        List<Map<String, Object>> paginas = new ArrayList<>();
        Map<String, Integer> resumo = new LinkedHashMap<>();
        for (MetodoClassificacao metodo : MetodoClassificacao.values()) {
            resumo.put(metodo.name(), 0);
        }

        for (int i = 0; i < classificacoes.size(); i++) {
            PaginaClassificada p = classificacoes.get(i);
            Map<String, Object> entrada = new LinkedHashMap<>();
            entrada.put("pagina", i + 1);
            entrada.put("categoria", p.getCategoria().getPastaSaida());
            entrada.put("metodo", p.getMetodo().name());
            paginas.add(entrada);

            resumo.merge(p.getMetodo().name(), 1, Integer::sum);
        }

        Map<String, Object> relatorio = new LinkedHashMap<>();
        relatorio.put("totalPaginas", classificacoes.size());
        relatorio.put("resumoPorMetodo", resumo);
        relatorio.put("paginas", paginas);

        return objectMapper.writeValueAsBytes(relatorio);
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

package com.rafael.pdfsplitter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Categorias de TFD que seguem a regra de bloco (capa fixa + páginas de continuação sem texto). */
    private static final Set<Categoria> CATEGORIAS_TFD = EnumSet.of(Categoria.TFD_RS, Categoria.TFD_OUTROS_ESTADOS);

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
            estenderBlocosTfd(classificacoes);
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
        if (contemAlgumaPalavra(textoNormalizado, config.tfdOutrosEstados())) {
            return Categoria.TFD_OUTROS_ESTADOS;
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
     * Um pedido de TFD (RS ou de outro estado) quase sempre ocupa um bloco de
     * páginas em sequência (capa do formulário + anexos que acompanham o
     * pedido, como documentos pessoais e exames que justificam a solicitação).
     * Só a 1ª página do bloco costuma trazer as palavras-chave do formulário;
     * as seguintes podem até ser classificadas como DOCUMENTOS, EXAMES etc.
     * isoladamente, mas continuam fazendo parte do MESMO pedido de TFD.
     *
     * Por isso, ao detectar o gatilho, as próximas páginas até completar o
     * tamanho do bloco (config: classificador.tfd-rs-paginas-por-bloco) entram
     * na mesma categoria do gatilho — EXCETO se uma delas for, ela mesma, o
     * início de OUTRO pedido de TFD (RS ou de outro estado), o que indica que
     * um novo bloco está começando ali e não deve ser absorvido pelo anterior.
     */
    private void estenderBlocosTfd(List<PaginaClassificada> classificacoes) {
        List<Categoria> original = new ArrayList<>();
        for (PaginaClassificada p : classificacoes) {
            original.add(p.getCategoria());
        }

        int tamanhoBloco = Math.max(1, config.tfdRsPaginasPorBloco());
        int totalPaginas = classificacoes.size();

        for (int i = 0; i < totalPaginas; i++) {
            Categoria categoriaDoBloco = original.get(i);
            if (!CATEGORIAS_TFD.contains(categoriaDoBloco)) {
                continue;
            }
            int fimBloco = Math.min(totalPaginas, i + tamanhoBloco);
            for (int j = i + 1; j < fimBloco; j++) {
                if (CATEGORIAS_TFD.contains(original.get(j))) {
                    // Um novo pedido de TFD começa aqui (mesmo que seja outro
                    // estado) — não faz parte do bloco anterior, para de estender.
                    break;
                }
                classificacoes.get(j).setCategoria(categoriaDoBloco);
                classificacoes.get(j).setMetodo(MetodoClassificacao.REGRA_BLOCO_TFD);
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

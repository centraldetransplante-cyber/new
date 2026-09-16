package com.rafael.pdfsplitter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rafael.pdfsplitter.gemini.ClassificacaoIa;
import com.rafael.pdfsplitter.gemini.GeminiClassificadorService;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PdfSplitService {

    private static final Logger LOG = Logger.getLogger(PdfSplitService.class);

    /** Categorias de TFD que seguem a regra de bloco (capa fixa + páginas de continuação sem texto). */
    private static final Set<Categoria> CATEGORIAS_TFD = EnumSet.of(Categoria.TFD_RS, Categoria.TFD_OUTROS_ESTADOS);

    /** Limite de páginas detalhadas no relatório (o cabeçalho HTTP não pode crescer sem limite). */
    private static final int LIMITE_PAGINAS_NO_RELATORIO = 500;

    /** Tamanho do pool compartilhado usado para paralelizar as chamadas ao Gemini (uma por página). */
    private static final int MAX_PARALELISMO_CLASSIFICACAO = 8;

    private final ClassificadorConfig config;
    private final GeminiClassificadorService geminiService;
    private final ObjectMapper objectMapper;

    /**
     * Pool compartilhado entre todas as requisições (não um pool novo por
     * upload) — criar/destruir 8 threads a cada PDF enviado multiplicaria o
     * overhead de memória exatamente no ambiente (Render free tier) que já é
     * limitado, e é justamente por causa dessa limitação que o resto do
     * código evita bufferizar o PDF inteiro em memória.
     */
    private final ExecutorService executorClassificacao = Executors.newFixedThreadPool(MAX_PARALELISMO_CLASSIFICACAO);

    public PdfSplitService(ClassificadorConfig config, GeminiClassificadorService geminiService, ObjectMapper objectMapper) {
        this.config = config;
        this.geminiService = geminiService;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void encerrar() {
        executorClassificacao.shutdown();
    }

    /**
     * Lê o PDF de entrada (direto do arquivo temporário do upload, sem
     * carregar tudo em memória duas vezes), classifica cada página e devolve
     * um .zip (em memória) com um único PDF por categoria (sem pastas),
     * juntando todas as páginas daquela categoria na ordem em que aparecem no
     * documento original, além de um relatório em JSON dizendo como cada
     * página foi classificada — esse relatório não entra no zip, é devolvido
     * à parte para a UI exibir.
     *
     * @throws PdfInvalidoException se o arquivo não for um PDF válido, estiver
     *         protegido por senha ou não tiver páginas.
     */
    public ResultadoSeparacao separar(File arquivoPdf) throws IOException {
        PDDocument origem;
        try {
            origem = Loader.loadPDF(arquivoPdf);
        } catch (InvalidPasswordException e) {
            throw new PdfInvalidoException("O PDF está protegido por senha. Remova a senha e envie novamente.", e);
        } catch (IOException e) {
            throw new PdfInvalidoException("O arquivo enviado não é um PDF válido ou está corrompido.", e);
        }

        try (origem) {
            if (origem.getNumberOfPages() == 0) {
                throw new PdfInvalidoException("O PDF enviado não tem páginas.");
            }
            achatarFormulario(origem);

            List<PaginaClassificada> classificacoes = classificarPaginas(origem);
            estenderBlocosTfd(classificacoes);
            Map<Categoria, List<Integer>> paginasPorCategoria = agruparPorCategoria(classificacoes);
            byte[] zip = montarZip(origem, paginasPorCategoria);
            String relatorioJson = new String(gerarRelatorioJson(classificacoes), java.nio.charset.StandardCharsets.UTF_8);
            return new ResultadoSeparacao(zip, relatorioJson);
        }
    }

    /**
     * Achata (flatten) os campos de formulário (AcroForm) do PDF de origem
     * antes de separar as páginas. Sem isso, um formulário de TFD preenchido
     * eletronicamente (e não "achatado" na origem) pode sair com os campos em
     * branco no PDF separado, porque {@code importPage} copia o conteúdo da
     * página mas não o valor dos campos do formulário. Não é um erro fatal se
     * falhar — só um risco visual a menos coberto.
     */
    private void achatarFormulario(PDDocument documento) {
        try {
            PDAcroForm acroForm = documento.getDocumentCatalog().getAcroForm();
            if (acroForm != null) {
                acroForm.flatten();
            }
        } catch (Exception e) {
            LOG.warn("Falha ao achatar formulário (AcroForm) do PDF de origem — campos podem sair em branco nas páginas separadas", e);
        }
    }

    /**
     * Extrai o texto de cada página (sequencial, é CPU local) e depois
     * classifica em paralelo (as chamadas ao Gemini são I/O de rede — um PDF
     * de 50 páginas em série podia passar de vários minutos e esbarrar em
     * timeout do navegador/proxy).
     */
    private List<PaginaClassificada> classificarPaginas(PDDocument documento) throws IOException {
        int totalPaginas = documento.getNumberOfPages();
        List<String> textos = new ArrayList<>(totalPaginas);
        PDFTextStripper stripper = new PDFTextStripper();

        for (int pagina = 1; pagina <= totalPaginas; pagina++) {
            stripper.setStartPage(pagina);
            stripper.setEndPage(pagina);
            textos.add(stripper.getText(documento));
        }

        List<Future<PaginaClassificada>> futuros = new ArrayList<>(totalPaginas);
        for (String texto : textos) {
            Callable<PaginaClassificada> tarefa = () -> classificarTexto(texto);
            futuros.add(executorClassificacao.submit(tarefa));
        }

        List<PaginaClassificada> resultado = new ArrayList<>(totalPaginas);
        for (Future<PaginaClassificada> futuro : futuros) {
            try {
                resultado.add(futuro.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Classificação interrompida", e);
            } catch (ExecutionException e) {
                throw new IOException("Falha ao classificar página", e.getCause());
            }
        }
        return resultado;
    }

    /**
     * Classifica uma página: o Gemini decide, e só caímos para a lista de
     * palavras-chave quando ele falha (sem rede, quota, resposta inválida) —
     * ou quando ele responde OUTROS (ver exceção abaixo). Página sem texto
     * extraível (digitalização sem OCR) nem chega a ser mandada pro Gemini:
     * não há o que analisar, e gastar uma chamada nisso só atrasa e consome
     * cota à toa.
     *
     * Exceção: quando o Gemini responde OUTROS. Esse é o "balde" que engolia
     * páginas que a lista de palavras-chave reconheceria sem dúvida (RG, CPF,
     * certidão, comprovante de residência, declaração, procuração, cartão do
     * SUS, encaminhamento...), fazendo documentos saírem dentro de outros.pdf.
     * Nesse caso — e SÓ nesse — conferimos o texto contra as palavras-chave; se
     * elas apontarem uma categoria real, ela prevalece sobre o OUTROS do
     * Gemini. A rede de segurança NUNCA promove para uma categoria de TFD:
     * uma página solta cujo texto corrido só menciona "TFD" de passagem (uma
     * carta, um aviso) não pode virar gatilho de bloco e arrastar as 2
     * páginas vizinhas pro lugar errado. O método registrado passa a ser
     * PALAVRA_CHAVE, porque foi de fato a lista que decidiu a categoria final.
     * O sinal de início/continuação do Gemini é preservado (ele não depende
     * da categoria).
     */
    private PaginaClassificada classificarTexto(String textoOriginal) {
        String textoNormalizado = normalizar(textoOriginal);
        if (textoNormalizado.isBlank()) {
            return new PaginaClassificada(Categoria.OUTROS, MetodoClassificacao.SEM_TEXTO, textoNormalizado, null);
        }

        ClassificacaoIa viaIa = geminiService.classificar(textoOriginal);
        if (viaIa != null) {
            if (viaIa.categoria() == Categoria.OUTROS) {
                Categoria redeDeSeguranca = classificarPorPalavraChave(textoNormalizado);
                if (redeDeSeguranca != Categoria.OUTROS && !CATEGORIAS_TFD.contains(redeDeSeguranca)) {
                    return new PaginaClassificada(redeDeSeguranca, MetodoClassificacao.PALAVRA_CHAVE,
                            textoNormalizado, viaIa.inicioDocumento());
                }
            }
            return new PaginaClassificada(viaIa.categoria(), MetodoClassificacao.GEMINI, textoNormalizado,
                    viaIa.inicioDocumento());
        }
        Categoria viaPalavraChave = classificarPorPalavraChave(textoNormalizado);
        return new PaginaClassificada(viaPalavraChave, MetodoClassificacao.PALAVRA_CHAVE, textoNormalizado, null);
    }

    /**
     * TFD_RS x TFD_OUTROS_ESTADOS exige as DUAS coisas na mesma página: um
     * termo genérico de TFD ({@link ClassificadorConfig#tfdTermoGenerico()})
     * E um marcador do RS ({@link ClassificadorConfig#tfdRsMarcador()}).
     * Marcador do RS sozinho (ex.: "central estadual de transplantes" numa
     * página que não é sobre TFD, comum aqui já que é uma central de
     * transplantes) NÃO classifica como TFD — evita falso positivo.
     */
    private Categoria classificarPorPalavraChave(String textoNormalizado) {
        boolean tfdGenerico = contemAlgumaPalavra(textoNormalizado, config.tfdTermoGenerico());
        boolean marcadorRs = contemAlgumaPalavra(textoNormalizado, config.tfdRsMarcador());
        if (tfdGenerico && marcadorRs) {
            return Categoria.TFD_RS;
        }
        if (tfdGenerico) {
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

    /**
     * Palavras-chave muito curtas (ex.: "rg") não usam contains puro, senão
     * batem dentro de qualquer palavra que contenha essas letras em sequência
     * ("urgente", "orgao", "cirurgia", "energia"...) — usa-se \b (borda de
     * palavra) só para essas. Palavras mais longas continuam com contains,
     * que já tolera variações de pontuação/plural ao redor.
     */
    private boolean contemAlgumaPalavra(String textoNormalizado, List<String> palavrasChave) {
        for (String palavra : palavrasChave) {
            String normalizada = normalizar(palavra);
            if (normalizada.length() <= 3) {
                if (Pattern.compile("\\b" + Pattern.quote(normalizada) + "\\b").matcher(textoNormalizado).find()) {
                    return true;
                }
            } else if (textoNormalizado.contains(normalizada)) {
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
     * Por isso, ao detectar o gatilho: primeiro tenta puxar pra trás a capa
     * real, caso a própria página-gatilho seja continuação (ver
     * {@link #puxarCapaParaTras}); depois estende pra frente até completar o
     * tamanho do bloco (config: classificador.tfd-rs-paginas-por-bloco),
     * parando em qualquer página que deva interromper a extensão (ver
     * {@link #deveInterromperBloco}).
     *
     * Páginas já absorvidas por um bloco anterior não abrem bloco próprio,
     * senão uma página de continuação que isoladamente caiu em TFD arrastaria
     * as páginas seguintes para a categoria errada.
     */
    private void estenderBlocosTfd(List<PaginaClassificada> classificacoes) {
        List<Categoria> original = new ArrayList<>();
        for (PaginaClassificada p : classificacoes) {
            original.add(p.getCategoria());
        }

        int tamanhoBloco = Math.max(1, config.tfdRsPaginasPorBloco());
        int totalPaginas = classificacoes.size();
        boolean[] absorvidaPorBlocoAnterior = new boolean[totalPaginas];

        for (int i = 0; i < totalPaginas; i++) {
            Categoria categoriaDoBloco = original.get(i);
            if (!CATEGORIAS_TFD.contains(categoriaDoBloco) || absorvidaPorBlocoAnterior[i]) {
                continue;
            }

            int puxadasParaTras = puxarCapaParaTras(classificacoes, i, categoriaDoBloco, tamanhoBloco);

            // O total de páginas do bloco (capa puxada pra trás + gatilho +
            // continuação pra frente) não pode passar de tamanhoBloco, senão
            // um bloco de 3 páginas configurado vira um de até 5 na prática.
            int fimBloco = Math.min(totalPaginas, i + tamanhoBloco - puxadasParaTras);
            for (int j = i + 1; j < fimBloco; j++) {
                if (deveInterromperBloco(categoriaDoBloco, classificacoes.get(j), original.get(j))) {
                    break;
                }
                classificacoes.get(j).setCategoria(categoriaDoBloco);
                classificacoes.get(j).setMetodo(MetodoClassificacao.REGRA_BLOCO_TFD);
                absorvidaPorBlocoAnterior[j] = true;
            }
        }
    }

    /**
     * Se a própria página-gatilho foi marcada pelo Gemini como CONTINUAÇÃO
     * (não INÍCIO), a capa real do pedido provavelmente ficou pra trás — por
     * exemplo uma digitalização ruim que não deixou o cabeçalho legível — e
     * caiu em OUTROS por falta de sinal próprio. Busca páginas OUTROS logo
     * antes do gatilho e absorve no mesmo bloco também, senão a capa se
     * perde. Só puxa páginas que hoje estão em OUTROS (nunca rouba página já
     * absorvida por outro bloco). Devolve quantas páginas foram puxadas, para
     * que a extensão pra frente desconte esse tanto e o bloco inteiro não
     * ultrapasse tamanhoBloco páginas no total.
     */
    private int puxarCapaParaTras(List<PaginaClassificada> classificacoes, int indiceGatilho,
            Categoria categoriaDoBloco, int tamanhoBloco) {
        if (!Boolean.FALSE.equals(classificacoes.get(indiceGatilho).getInicioDocumento())) {
            return 0;
        }
        int k = indiceGatilho - 1;
        int puxadas = 0;
        while (k >= 0 && puxadas < tamanhoBloco - 1 && classificacoes.get(k).getCategoria() == Categoria.OUTROS) {
            classificacoes.get(k).setCategoria(categoriaDoBloco);
            classificacoes.get(k).setMetodo(MetodoClassificacao.REGRA_BLOCO_TFD);
            k--;
            puxadas++;
        }
        return puxadas;
    }

    /**
     * Decide se a página, que está DENTRO da janela de um bloco de TFD já
     * iniciado, deve interromper a extensão (ou seja: não faz parte do mesmo
     * pedido).
     */
    private boolean deveInterromperBloco(Categoria categoriaDoBloco, PaginaClassificada pagina, Categoria categoriaIsolada) {
        if (CATEGORIAS_TFD.contains(categoriaIsolada)) {
            // Seja da MESMA categoria do bloco ou de uma categoria de TFD
            // DIFERENTE, uma página aqui dentro pode ser só um anexo do MESMO
            // pedido: o exemplo real que expôs isso é o laudo médico emitido
            // pela secretaria de saúde do ESTADO DE ORIGEM do paciente,
            // anexado para justificar um pedido de TFD/RS — tem timbre/título
            // institucional próprio (por isso bate isoladamente em
            // TFD_OUTROS_ESTADOS), mas continua sendo parte do MESMO bloco
            // do RS, não uma segunda solicitação. Só interrompe com sinal
            // FORTE e específico de nova capa (Gemini INICIO, ou o texto
            // batendo no cabeçalho exclusivo do formulário de cadastro do
            // RS) — nunca só por a categoria isolada ser diferente da do
            // bloco. Ver iniciaNovoPedidoTfd.
            return iniciaNovoPedidoTfd(pagina, categoriaIsolada);
        }
        if (categoriaIsolada == Categoria.OUTROS) {
            return false;
        }
        // Categoria "real" diferente (documentos, exames, protocolo...)
        // dentro da janela: sem sinal próprio presumimos que é anexo do
        // mesmo pedido de TFD (regra do negócio: nunca perder página de
        // continuação). Só interrompe se o Gemini disse CLARAMENTE que ali
        // começa um documento novo.
        return Boolean.TRUE.equals(pagina.getInicioDocumento());
    }

    /**
     * Decide se a página, que está DENTRO da janela de um bloco de TFD já
     * iniciado, é na verdade a CAPA de um novo pedido de TFD DA MESMA
     * categoria (e portanto deve interromper a extensão do bloco anterior).
     *
     * A classificação isolada da página não basta: as listas de palavras-chave
     * de TFD são genéricas de propósito ("tfd", "tratamento fora de
     * domicilio"...) e essas expressões aparecem no texto corrido das páginas
     * de CONTINUAÇÃO do próprio formulário (o checklist e a justificativa
     * falam SOBRE o tratamento fora de domicílio).
     *
     * Por isso exigimos um sinal FORTE de início de documento:
     * <ul>
     *   <li>Gemini (que lê o texto da página) dizendo explicitamente que a
     *       página é INÍCIO de documento — e, se ele disser CONTINUAÇÃO, a
     *       página nunca quebra o bloco, mesmo com categoria de TFD;</li>
     *   <li>ou, sem esse sinal (fallback por palavra-chave / resposta antiga do
     *       Gemini), texto batendo em classificador.tfd-cabecalho-novo-documento,
     *       que só tem termos de cabeçalho/timbre/título de formulário.</li>
     * </ul>
     */
    private boolean iniciaNovoPedidoTfd(PaginaClassificada pagina, Categoria categoriaIsolada) {
        if (!CATEGORIAS_TFD.contains(categoriaIsolada)) {
            return false;
        }
        if (!config.tfdExigirCabecalhoParaQuebrarBloco()) {
            // Comportamento antigo, mantido só como escape via configuração.
            return true;
        }

        Boolean inicioDocumento = pagina.getInicioDocumento();
        if (inicioDocumento != null) {
            // Sinal do Gemini: mais confiável que substring de palavra-chave.
            return inicioDocumento;
        }
        return contemAlgumaPalavra(pagina.getTextoNormalizado(), config.tfdCabecalhoNovoDocumento());
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

    /**
     * Relatório por página, com contagem por método sempre completa (todas as
     * páginas) mas a lista detalhada por página limitada a
     * {@link #LIMITE_PAGINAS_NO_RELATORIO} — em PDFs muito grandes, um
     * cabeçalho HTTP sem limite pode ser cortado pelo proxy do Render e o
     * relatório inteiro some sem explicação; melhor um relatório parcial e
     * avisado (campo "paginasTruncadas") do que nenhum.
     */
    private byte[] gerarRelatorioJson(List<PaginaClassificada> classificacoes) throws IOException {
        List<Map<String, Object>> paginas = new ArrayList<>();
        Map<String, Integer> resumo = new LinkedHashMap<>();
        for (MetodoClassificacao metodo : MetodoClassificacao.values()) {
            resumo.put(metodo.name(), 0);
        }

        boolean truncado = classificacoes.size() > LIMITE_PAGINAS_NO_RELATORIO;
        for (int i = 0; i < classificacoes.size(); i++) {
            PaginaClassificada p = classificacoes.get(i);
            resumo.merge(p.getMetodo().name(), 1, Integer::sum);

            if (i < LIMITE_PAGINAS_NO_RELATORIO) {
                Map<String, Object> entrada = new LinkedHashMap<>();
                entrada.put("pagina", i + 1);
                entrada.put("categoria", p.getCategoria().getPastaSaida());
                entrada.put("metodo", p.getMetodo().name());
                paginas.add(entrada);
            }
        }

        Map<String, Object> relatorio = new LinkedHashMap<>();
        relatorio.put("totalPaginas", classificacoes.size());
        relatorio.put("resumoPorMetodo", resumo);
        relatorio.put("paginas", paginas);
        relatorio.put("paginasTruncadas", truncado);

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

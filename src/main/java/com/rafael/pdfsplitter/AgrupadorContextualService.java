package com.rafael.pdfsplitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jboss.logging.Logger;

import com.rafael.pdfsplitter.gemini.DocumentoDetectado;
import com.rafael.pdfsplitter.gemini.GeminiClassificadorService;
import com.rafael.pdfsplitter.gemini.ResultadoAgrupamento;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Classificação com contexto (modo {@code classificador.modo=CONTEXTO}, o padrão): em vez de classificar cada
 * página isolada e tentar remendar blocos de TFD depois (modo {@code PAGINA}, ver {@link PdfSplitService}), manda
 * ao Gemini janelas de várias páginas de uma vez — com as páginas anteriores como contexto — e pede para ele
 * AGRUPAR diretamente em documentos. Isso resolve na raiz o bug que motivou esse redesenho: um anexo com timbre
 * próprio (ex.: laudo médico de outro estado dentro de um pedido de TFD/RS) sendo confundido com a capa de um
 * pedido novo, porque o modelo agora vê várias páginas em sequência, não uma por vez.
 */
@ApplicationScoped
public class AgrupadorContextualService {

    private static final Logger LOG = Logger.getLogger(AgrupadorContextualService.class);

    private static final Set<Categoria> CATEGORIAS_TFD = Categoria.CATEGORIAS_TFD;

    /** Abaixo dessa fração de páginas resolvidas numa janela, a janela inteira é tratada como falha. */
    private static final double COBERTURA_MINIMA = 0.6;

    private final ClassificadorConfig config;
    private final GeminiClassificadorService geminiService;
    private final ClassificadorPalavraChaveService palavraChave;

    public AgrupadorContextualService(ClassificadorConfig config, GeminiClassificadorService geminiService,
            ClassificadorPalavraChaveService palavraChave) {
        this.config = config;
        this.geminiService = geminiService;
        this.palavraChave = palavraChave;
    }

    /**
     * Estado do "bundle" (documento físico, tipicamente TFD) que termina numa determinada página — guardado POR
     * PÁGINA (não como uma única variável de "documento aberto") para que a costura entre documentos/janelas
     * funcione olhando sempre para a página anterior de verdade, mesmo quando ela foi resolvida por um caminho
     * diferente (um {@code DocumentoDetectado} do Gemini, ou herdada de um buraco/página não resolvida) — bug
     * real corrigido em 2026-09-17: antes disso, o estado só era atualizado dentro do laço de documentos, então um
     * buraco no fim de uma janela "esquecia" a página final do bundle e quebrava a costura para a janela seguinte.
     *
     * Guarda separadamente a categoria BRUTA (a que o Gemini deu, antes de qualquer rebaixamento) da EFETIVA (a
     * aplicada de fato): a costura de "mesmo documento" compara bruta com bruta, nunca bruta com efetiva — outro
     * bug real corrigido junto: comparar bruta com efetiva fazia um rebaixamento no meio de um bundle contaminar
     * irreversivelmente a costura de todo o resto dele.
     */
    private record EstadoBundle(Categoria categoriaBruta, Categoria categoriaEfetiva, boolean rsConfirmado) {
    }

    public List<PaginaClassificada> classificar(List<String> textosPorPagina) {
        int total = textosPorPagina.size();
        List<String> normalizados = new ArrayList<>(total);
        for (String texto : textosPorPagina) {
            normalizados.add(palavraChave.normalizar(texto));
        }

        Categoria[] categoriaFinal = new Categoria[total];
        MetodoClassificacao[] metodoFinal = new MetodoClassificacao[total];
        EstadoBundle[] estadoPorPagina = new EstadoBundle[total];

        int porJanela = Math.max(1, config.contextoPaginasPorJanela());
        int paginasDeContexto = Math.max(0, config.contextoPaginasDeContexto());
        int maxChars = Math.max(200, config.contextoMaxCaracteresPorPagina());

        int inicioJanela = 0; // 0-based, indice da 1a pagina de decisao da janela
        while (inicioJanela < total) {
            int tamanhoJanela = Math.min(porJanela, total - inicioJanela);
            int inicioContexto = Math.max(0, inicioJanela - paginasDeContexto);

            List<String> textosContexto = new ArrayList<>();
            for (int k = inicioContexto; k < inicioJanela; k++) {
                textosContexto.add(truncar(textosPorPagina.get(k), maxChars));
            }
            List<String> textosDecisao = new ArrayList<>();
            for (int k = inicioJanela; k < inicioJanela + tamanhoJanela; k++) {
                textosDecisao.add(truncar(textosPorPagina.get(k), maxChars));
            }

            int primeiraPaginaDecisao = inicioJanela + 1;
            int ultimaPaginaDecisao = inicioJanela + tamanhoJanela;

            ResultadoAgrupamento resultado = geminiService.disponivel()
                    ? geminiService.agrupar(textosContexto, inicioContexto + 1, textosDecisao, primeiraPaginaDecisao)
                    : null;

            double cobertura = resultado == null ? 0.0
                    : 1.0 - (resultado.paginasNaoResolvidas().size() / (double) tamanhoJanela);

            if (resultado == null || cobertura < COBERTURA_MINIMA) {
                LOG.warnf("Janela de páginas %d-%d sem cobertura suficiente do Gemini (%.0f%%) - usando fallback "
                        + "por palavra-chave para essa janela", primeiraPaginaDecisao, ultimaPaginaDecisao, cobertura * 100);
                for (int k = inicioJanela; k < inicioJanela + tamanhoJanela; k++) {
                    categoriaFinal[k] = normalizados.get(k).isBlank()
                            ? Categoria.OUTROS
                            : palavraChave.classificar(normalizados.get(k));
                    metodoFinal[k] = normalizados.get(k).isBlank() ? MetodoClassificacao.SEM_TEXTO
                            : MetodoClassificacao.PALAVRA_CHAVE;
                    // Não tenta costurar um bloco de TFD por cima de um trecho que caiu no fallback.
                    estadoPorPagina[k] = null;
                }
            } else {
                LOG.debugf("Janela de páginas %d-%d: %d documento(s) detectado(s), %d página(s) não resolvida(s)",
                        primeiraPaginaDecisao, ultimaPaginaDecisao, resultado.documentos().size(),
                        resultado.paginasNaoResolvidas().size());

                for (DocumentoDetectado doc : resultado.documentos()) {
                    Categoria categoriaBruta = doc.categoria();
                    EstadoBundle estadoAnterior = estadoDaPaginaAnterior(doc.paginaInicial(), estadoPorPagina);

                    // Uma página que confirma o RS por conta própria (tem marcador real do RS na própria página)
                    // é um sinal forte e nunca deve ser preterida pela absorção por página pobre abaixo — mesmo
                    // que o texto seja curto, um marcador de verdade pesa mais que "está contíguo a um bundle".
                    boolean autoconfirmaRs = categoriaBruta == Categoria.TFD_RS
                            && algumaPaginaConfirmaRs(normalizados, doc.paginaInicial(), doc.paginaFinal());

                    boolean todasPaginasPobres = todasPobres(normalizados, doc.paginaInicial(), doc.paginaFinal());
                    boolean temCabecalhoDeNovoDocumento = algumaPaginaTemCabecalhoNovoDocumento(normalizados,
                            doc.paginaInicial(), doc.paginaFinal());
                    // Não é só um booleano "tem sinal" - guarda QUAL categoria própria foi detectada, pra poder
                    // rotear pra ela diretamente em vez de só bloquear a absorção/fabricação e cair num OUTROS
                    // genérico quando na verdade dava pra saber que era, por exemplo, DOCUMENTOS.
                    Categoria categoriaAutoEvidente = categoriaAutoEvidenteOuNull(normalizados, doc.paginaInicial(), doc.paginaFinal());
                    boolean temSinalDeCategoriaPropria = categoriaAutoEvidente != null;
                    boolean bundleTfdAberto = estadoAnterior != null && CATEGORIAS_TFD.contains(estadoAnterior.categoriaEfetiva());

                    // Absorção: um trecho contíguo a um bundle de TFD aberto, sem NENHUM conteúdo real (só carimbo
                    // de protocolo/boilerplate — comum em anexo escaneado sem OCR), sem cabeçalho de novo pedido,
                    // sem palavra-chave de categoria própria (um RG/CPF curto tem pouco texto mas não é "sem
                    // conteúdo") e sem conseguir se autoconfirmar como RS por conta própria, é tratado como
                    // continuação do bundle aberto, INDEPENDENTE da categoria bruta que o Gemini deu a ele. Bug
                    // real que motivou isto: páginas assim saíam com a categoria "adivinhada" pelo Gemini a partir
                    // de quase nada (ex.: TFD_OUTROS_ESTADOS), fora do bundle TFD_RS ao qual pertenciam de verdade.
                    boolean absorverNoBundle = !autoconfirmaRs && !temSinalDeCategoriaPropria && bundleTfdAberto
                            && todasPaginasPobres && !temCabecalhoDeNovoDocumento;

                    Categoria categoriaEfetiva;
                    Categoria categoriaBrutaParaEstado;
                    boolean rsConfirmadoAqui;
                    boolean continuaMesmaCategoriaBruta;
                    // Não nulo quando a categoria final veio de uma decisão do Java baseada em palavra-chave (o
                    // reroteamento pra categoria própria, ou o rebaixamento pra OUTROS por falta de conteúdo) em
                    // vez de vir do Gemini - o relatório de classificação precisa refletir isso, senão os totais
                    // por método subestimam o quanto a rede de segurança do Java realmente decidiu.
                    MetodoClassificacao metodoForcado = null;

                    if (absorverNoBundle) {
                        categoriaEfetiva = estadoAnterior.categoriaEfetiva();
                        categoriaBrutaParaEstado = estadoAnterior.categoriaBruta();
                        rsConfirmadoAqui = estadoAnterior.rsConfirmado();
                        continuaMesmaCategoriaBruta = true;
                        LOG.infof("Documento páginas %d-%d (categoria bruta do Gemini: %s) sem conteúdo real (só "
                                + "carimbo/boilerplate) e contíguo a um bundle %s aberto - absorvido no bundle em "
                                + "vez de virar um documento novo", doc.paginaInicial(), doc.paginaFinal(), categoriaBruta,
                                categoriaEfetiva);
                    } else {
                        // Continua o documento aberto na janela anterior se o Gemini deu a MESMA categoria BRUTA
                        // (nunca compara com a categoria já rebaixada/efetiva - ver javadoc de EstadoBundle) e for
                        // contíguo — é essa continuidade que decide se um TFD_RS sem marcador próprio (ex.: o
                        // laudo médico anexado, que não menciona o RS) ainda conta como confirmado por herdar a
                        // confirmação do documento que ele continua.
                        continuaMesmaCategoriaBruta = estadoAnterior != null
                                && categoriaBruta == estadoAnterior.categoriaBruta()
                                && CATEGORIAS_TFD.contains(categoriaBruta);

                        categoriaEfetiva = categoriaBruta;
                        rsConfirmadoAqui = autoconfirmaRs;
                        // O reroteamento por palavra-chave própria e o rebaixamento pra OUTROS só valem quando a
                        // página realmente não tem conteúdo (todasPaginasPobres) - um documento TFD longo e com
                        // conteúdo clínico real que por acaso menciona uma palavra de outra lista (ex.:
                        // "encaminhamento" no meio do texto corrido) não pode ser arrancado do bundle por isso.
                        if (categoriaBruta == Categoria.TFD_RS && !autoconfirmaRs && todasPaginasPobres
                                && temSinalDeCategoriaPropria) {
                            // Palavra-chave de RG/CPF/protocolo própria vence mesmo quando o Gemini deu a mesma
                            // categoria bruta que um documento anterior confirmado (o que herdaria a confirmação
                            // abaixo) - um RG anexado não vira TFD_RS só porque está contíguo e o Gemini repetiu a
                            // categoria por engano. NUNCA vence, porém, quando a própria página se autoconfirma
                            // como RS (marcador do RS de verdade) - um sinal de conteúdo real sempre pesa mais que
                            // uma correspondência de palavra-chave incidental.
                            categoriaEfetiva = categoriaAutoEvidente;
                            rsConfirmadoAqui = false;
                            metodoForcado = MetodoClassificacao.PALAVRA_CHAVE;
                            LOG.infof("Documento páginas %d-%d marcado TFD_RS pelo Gemini mas tem palavra-chave "
                                    + "própria de %s, classificando como tal", doc.paginaInicial(), doc.paginaFinal(),
                                    categoriaAutoEvidente);
                        } else if (categoriaBruta == Categoria.TFD_RS) {
                            if (!rsConfirmadoAqui && continuaMesmaCategoriaBruta && estadoAnterior.rsConfirmado()) {
                                // Nenhuma página DESTE trecho bate no marcador do RS, mas ele é a continuação
                                // direta de um documento que já tinha sido confirmado - exatamente o caso do laudo
                                // médico de outro estado anexado ao pedido do RS, que naturalmente não menciona o RS.
                                rsConfirmadoAqui = true;
                            }
                            if (!rsConfirmadoAqui) {
                                if (todasPaginasPobres && !temCabecalhoDeNovoDocumento) {
                                    // Sem conteúdo real, sem cabeçalho de novo pedido (esse cabeçalho já seria, por
                                    // si só, sinal de categoria - ver o branch simétrico de TFD_OUTROS_ESTADOS
                                    // abaixo) e sem bundle aberto pra herdar de (senão teria caído em
                                    // absorverNoBundle acima) - fabricar TFD_OUTROS_ESTADOS a partir de zero
                                    // conteúdo seria uma afirmação de negócio falsa; OUTROS é honesto.
                                    categoriaEfetiva = Categoria.OUTROS;
                                    metodoForcado = MetodoClassificacao.PALAVRA_CHAVE;
                                    LOG.warnf("Documento páginas %d-%d marcado TFD_RS pelo Gemini mas sem conteúdo "
                                            + "real (só carimbo/boilerplate) e sem bundle RS aberto pra herdar - "
                                            + "classificando como OUTROS em vez de TFD_OUTROS_ESTADOS",
                                            doc.paginaInicial(), doc.paginaFinal());
                                } else {
                                    categoriaEfetiva = Categoria.TFD_OUTROS_ESTADOS;
                                    LOG.warnf("Documento páginas %d-%d marcado TFD_RS pelo Gemini mas nenhuma página "
                                            + "(nem a do documento anterior continuado) bate no marcador do RS - "
                                            + "rebaixando para TFD_OUTROS_ESTADOS", doc.paginaInicial(), doc.paginaFinal());
                                }
                            }
                        } else if (categoriaBruta == Categoria.TFD_OUTROS_ESTADOS && todasPaginasPobres
                                && !temCabecalhoDeNovoDocumento) {
                            // Mesmo raciocínio do guard de RS acima, mas para quando o Gemini já devolve
                            // TFD_OUTROS_ESTADOS direto (não é um rebaixamento) numa página sem conteúdo real e
                            // sem cabeçalho de novo pedido (esse cabeçalho já é, por si só, um sinal de categoria
                            // legítimo - não deve virar OUTROS só por ser curto). Não exige "sem bundle aberto": se
                            // houvesse um bundle aberto E nenhuma palavra-chave própria, o trecho já teria caído em
                            // absorverNoBundle acima - chegar aqui com bundleTfdAberto=true só acontece quando
                            // temSinalDeCategoriaPropria bloqueou a absorção, e nesse caso É pra rerotear pra
                            // categoria própria mesmo com bundle aberto.
                            categoriaEfetiva = temSinalDeCategoriaPropria ? categoriaAutoEvidente : Categoria.OUTROS;
                            metodoForcado = MetodoClassificacao.PALAVRA_CHAVE;
                            LOG.warnf("Documento páginas %d-%d marcado TFD_OUTROS_ESTADOS pelo Gemini mas sem "
                                    + "conteúdo real (só carimbo/boilerplate) - classificando como %s",
                                    doc.paginaInicial(), doc.paginaFinal(), categoriaEfetiva);
                        }
                        categoriaBrutaParaEstado = categoriaBruta;
                    }

                    EstadoBundle novoEstado = new EstadoBundle(categoriaBrutaParaEstado, categoriaEfetiva,
                            categoriaEfetiva == Categoria.TFD_RS && rsConfirmadoAqui);

                    for (int pagina1based = doc.paginaInicial(); pagina1based <= doc.paginaFinal(); pagina1based++) {
                        int idx = pagina1based - 1;
                        if (idx < 0 || idx >= total) {
                            continue;
                        }
                        categoriaFinal[idx] = categoriaEfetiva;
                        metodoFinal[idx] = metodoForcado != null ? metodoForcado
                                : absorverNoBundle || (continuaMesmaCategoriaBruta && pagina1based == doc.paginaInicial())
                                        ? MetodoClassificacao.REGRA_BLOCO_TFD
                                        : MetodoClassificacao.GEMINI_CONTEXTO;
                        estadoPorPagina[idx] = novoEstado;
                    }
                }

                for (int paginaNr : resultado.paginasNaoResolvidas()) {
                    int idx = paginaNr - 1;
                    if (idx < 0 || idx >= total) {
                        continue;
                    }
                    EstadoBundle estadoAnterior = estadoDaPaginaAnterior(paginaNr, estadoPorPagina);
                    if (estadoAnterior != null) {
                        // Reparo simples: buraco de 1-2 paginas no meio de um bloco ja identificado provavelmente
                        // e continuacao dele (regra do negocio: nao perder pagina) - so rotula como "regra de
                        // bloco TFD" quando a categoria herdada realmente for de TFD, senão o relatório mostraria
                        // esse rótulo numa página que não tem nada a ver com TFD.
                        categoriaFinal[idx] = estadoAnterior.categoriaEfetiva();
                        metodoFinal[idx] = CATEGORIAS_TFD.contains(estadoAnterior.categoriaEfetiva())
                                ? MetodoClassificacao.REGRA_BLOCO_TFD
                                : MetodoClassificacao.GEMINI_CONTEXTO;
                        estadoPorPagina[idx] = estadoAnterior;
                    } else {
                        categoriaFinal[idx] = normalizados.get(idx).isBlank()
                                ? Categoria.OUTROS
                                : palavraChave.classificar(normalizados.get(idx));
                        metodoFinal[idx] = normalizados.get(idx).isBlank() ? MetodoClassificacao.SEM_TEXTO
                                : MetodoClassificacao.PALAVRA_CHAVE;
                        estadoPorPagina[idx] = null;
                    }
                }
            }

            inicioJanela += tamanhoJanela;
        }

        List<PaginaClassificada> saida = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            Categoria categoria = categoriaFinal[i] != null ? categoriaFinal[i] : Categoria.OUTROS;
            MetodoClassificacao metodo = metodoFinal[i] != null ? metodoFinal[i] : MetodoClassificacao.PALAVRA_CHAVE;
            saida.add(new PaginaClassificada(categoria, metodo, normalizados.get(i), null));
        }
        return saida;
    }

    /**
     * Estado da página imediatamente anterior a {@code paginaAbsoluta} (1-based), ou {@code null} se não houver
     * (início do documento, ou a página anterior não pertence a nenhum bundle rastreado). Olhar sempre a página
     * anterior de verdade (em vez de uma variável só atualizada dentro do laço de documentos) é o que faz a
     * costura sobreviver a buracos/páginas não resolvidas e a limites de janela sem depender da ordem em que os
     * dois laços acima são executados.
     */
    private static EstadoBundle estadoDaPaginaAnterior(int paginaAbsoluta, EstadoBundle[] estadoPorPagina) {
        int idx = paginaAbsoluta - 2;
        return (idx >= 0 && idx < estadoPorPagina.length) ? estadoPorPagina[idx] : null;
    }

    private boolean todasPobres(List<String> normalizados, int paginaInicial, int paginaFinal) {
        for (int pagina = paginaInicial; pagina <= paginaFinal; pagina++) {
            if (pagina < 1 || pagina > normalizados.size()) {
                continue;
            }
            if (!palavraChave.paginaPobre(normalizados.get(pagina - 1))) {
                return false;
            }
        }
        return true;
    }

    private boolean algumaPaginaTemCabecalhoNovoDocumento(List<String> normalizados, int paginaInicial, int paginaFinal) {
        for (int pagina = paginaInicial; pagina <= paginaFinal; pagina++) {
            if (pagina < 1 || pagina > normalizados.size()) {
                continue;
            }
            if (palavraChave.contemAlgumaPalavra(normalizados.get(pagina - 1), config.tfdCabecalhoNovoDocumento())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Um documento curto (poucas letras de conteúdo útil) não é necessariamente um anexo escaneado sem OCR — pode
     * ser um documento pessoal genuinamente curto (cópia de RG/CPF, por exemplo, costuma ter pouco texto
     * extraível mesmo com conteúdo real). Se alguma página já bate numa palavra-chave de categoria própria,
     * devolve QUAL categoria (não só um booleano) — isso é sinal forte o bastante pra não ser absorvido/rebaixado
     * às cegas: em vez de só bloquear a absorção e cair num {@code OUTROS} genérico, roteia direto pra ela.
     *
     * Deliberadamente NÃO considera {@link ClassificadorConfig#exames()}: "laudo"/"exame" são exatamente os
     * termos do cenário que este arquivo existe pra tratar (regra de negócio #2 — um LAUDO MÉDICO é um anexo
     * legítimo do MESMO bundle de TFD/RS, mesmo lendo como EXAMES isoladamente). Usar essa lista aqui bloquearia a
     * absorção de um laudo médico pobre/mal-OCRizado contíguo a um bundle aberto, reproduzindo o mesmo bug que a
     * arquitetura de janela+agrupamento foi desenhada pra resolver. Só {@link ClassificadorConfig#documentos()}
     * (RG/CPF/identidade — nunca parte do conteúdo médico de um pedido de TFD, sempre um documento à parte
     * independente de contexto) e {@link ClassificadorConfig#protocoloEncaminhamento()} são sinais inequívocos o
     * bastante pra vencer a absorção/continuação. {@code null} se nenhuma bater.
     */
    private Categoria categoriaAutoEvidenteOuNull(List<String> normalizados, int paginaInicial, int paginaFinal) {
        // Duas passadas (não uma só) pra que a prioridade PROTOCOLO_ENCAMINHAMENTO > DOCUMENTOS valha pro
        // documento inteiro, não só pra primeira página que bater em alguma coisa - senão um documento de 2
        // páginas com "rg" na primeira e "protocolo de encaminhamento" na segunda voltaria DOCUMENTOS (a
        // categoria da página 1) em vez de PROTOCOLO_ENCAMINHAMENTO (a mais específica, presente em algum lugar
        // do documento).
        if (algumaPaginaContem(normalizados, paginaInicial, paginaFinal, config.protocoloEncaminhamento())) {
            return Categoria.PROTOCOLO_ENCAMINHAMENTO;
        }
        if (algumaPaginaContem(normalizados, paginaInicial, paginaFinal, config.identificacaoPessoalInequivoca())) {
            return Categoria.DOCUMENTOS;
        }
        return null;
    }

    private boolean algumaPaginaContem(List<String> normalizados, int paginaInicial, int paginaFinal, List<String> palavrasChave) {
        for (int pagina = paginaInicial; pagina <= paginaFinal; pagina++) {
            if (pagina < 1 || pagina > normalizados.size()) {
                continue;
            }
            // Remove o carimbo ANTES de checar palavra-chave - senão uma palavra que por acaso aparecesse dentro
            // do próprio texto do carimbo (ex. uma futura entrada nas listas de configuração que bata em algo do
            // boilerplate) daria um sinal de categoria falso numa página genuinamente sem conteúdo.
            String texto = palavraChave.removerCarimboDeProtocolo(normalizados.get(pagina - 1));
            if (palavraChave.contemAlgumaPalavra(texto, palavrasChave)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A categoria TFD_RS é um requisito de negócio crítico demais para confiar só no julgamento do Gemini sobre o
     * documento inteiro — confere em Java se pelo menos uma página do documento realmente bate nos termos
     * exclusivos do RS (mesma condição usada no fallback por palavra-chave).
     */
    private boolean algumaPaginaConfirmaRs(List<String> normalizados, int paginaInicial, int paginaFinal) {
        for (int pagina = paginaInicial; pagina <= paginaFinal; pagina++) {
            if (pagina < 1 || pagina > normalizados.size()) {
                continue;
            }
            String texto = normalizados.get(pagina - 1);
            if (palavraChave.contemAlgumaPalavra(texto, config.tfdRsMarcador())
                    && palavraChave.contemAlgumaPalavra(texto, config.tfdTermoGenerico())) {
                return true;
            }
        }
        return false;
    }

    private String truncar(String texto, int maxCaracteres) {
        if (texto == null) {
            return "";
        }
        String t = texto.trim();
        return t.length() <= maxCaracteres ? t : t.substring(0, maxCaracteres);
    }
}

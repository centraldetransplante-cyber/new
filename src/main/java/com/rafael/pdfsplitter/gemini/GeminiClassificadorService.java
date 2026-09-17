package com.rafael.pdfsplitter.gemini;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import com.rafael.pdfsplitter.Categoria;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class GeminiClassificadorService {

    private static final Logger LOG = Logger.getLogger(GeminiClassificadorService.class);

    @Inject
    @RestClient
    GeminiClient client;

    private final GeminiConfig config;

    public GeminiClassificadorService(GeminiConfig config) {
        this.config = config;
    }

    public boolean disponivel() {
        return config.apiKey() != null && !config.apiKey().isBlank();
    }

    private static final int MAX_TENTATIVAS = 2;

    /**
     * Pede ao Gemini para classificar o texto de uma página em uma das
     * categorias suportadas e, junto, dizer se a página é o INÍCIO de um
     * documento ou a CONTINUAÇÃO do documento anterior. Tenta até
     * {@value #MAX_TENTATIVAS} vezes (erros de rede/429 costumam ser
     * passageiros). Retorna null se todas as tentativas falharem, para que o
     * chamador use o fallback por palavra-chave.
     */
    public ClassificacaoIa classificar(String textoPagina) {
        if (!disponivel()) {
            return null;
        }
        String prompt = montarPrompt(textoPagina);
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            try {
                GeminiResponse resposta = client.gerarConteudo(config.modelo(), config.apiKey(), GeminiRequest.deTexto(prompt));
                return interpretarResposta(resposta.primeiroTexto());
            } catch (Exception e) {
                boolean permanente = erroPermanente(e);
                if (permanente || tentativa == MAX_TENTATIVAS) {
                    LOG.warnf("Falha ao classificar página com Gemini (%s%s) - usando fallback por palavra-chave",
                            detalheErro(e), permanente ? ", erro não é passageiro, não tentando de novo"
                                    : " após " + MAX_TENTATIVAS + " tentativa(s)");
                    return null;
                }
                LOG.debug("Falha ao classificar página com Gemini, tentando novamente", e);
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Um HTTP 4xx (exceto 408/429, que costumam ser passageiros - timeout no gateway, limite de taxa) indica um
     * problema de configuração/requisição que não se resolve tentando de novo (chave inválida, modelo inexistente,
     * schema rejeitado) - retentar só atrasa o fallback em ~400ms à toa. Erros de rede/timeout (sem
     * {@link Response} nenhuma) e 5xx continuam sendo tratados como passageiros.
     */
    private boolean erroPermanente(Exception e) {
        if (!(e instanceof WebApplicationException wae) || wae.getResponse() == null) {
            return false;
        }
        int status = wae.getResponse().getStatus();
        return status >= 400 && status < 500 && status != 408 && status != 429;
    }

    /**
     * Antes disso, um erro HTTP do Gemini (chave inválida, modelo não encontrado, schema rejeitado, quota
     * excedida...) só aparecia no log como a mensagem genérica do RESTEasy ("HTTP 400 Bad Request"), nunca com o
     * {@code error.message} de verdade que o Google devolve no corpo - tornando praticamente impossível distinguir,
     * só pelo log, qual das várias causas possíveis de "100% fallback" realmente aconteceu numa execução real.
     */
    private String detalheErro(Exception e) {
        if (e instanceof WebApplicationException wae && wae.getResponse() != null) {
            Response resposta = wae.getResponse();
            String corpo;
            try {
                corpo = resposta.hasEntity() ? resposta.readEntity(String.class) : null;
            } catch (Exception ignorada) {
                corpo = null;
            }
            String resumo = corpo == null || corpo.isBlank() ? "" : ": " + corpo.substring(0, Math.min(corpo.length(), 500));
            return "HTTP " + resposta.getStatus() + resumo;
        }
        return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }

    private String montarPrompt(String textoPagina) {
        return """
                Classifique o texto de UMA página de um PDF em exatamente uma das categorias abaixo e diga
                também se essa página é o INÍCIO de um documento ou a CONTINUAÇÃO do documento anterior.

                Responda APENAS em uma linha, no formato:
                CATEGORIA|INICIO   ou   CATEGORIA|CONTINUACAO
                (sem explicações, sem pontuação extra).

                Como decidir INICIO x CONTINUACAO:
                - INICIO: a página abre um documento novo. Tem cabeçalho/timbre institucional, brasão, nome do
                  órgão, título de formulário, número de protocolo/formulário no topo, ou é claramente a primeira
                  folha de um documento (capa, requerimento, laudo com cabeçalho próprio).
                - CONTINUACAO: a página é a 2ª, 3ª... folha do MESMO documento que vinha antes. Começa direto no
                  meio do conteúdo (itens de checklist, perguntas sim/não, "Queixa principal", história clínica,
                  assinaturas, campos de preenchimento) e NÃO repete o cabeçalho/timbre do formulário.
                - ATENÇÃO: uma página de continuação de um formulário de TFD frequentemente MENCIONA "TFD" ou
                  "tratamento fora de domicílio" no meio do texto corrido (justificando o pedido). Isso NÃO faz
                  dela o início de um documento novo — se não houver cabeçalho/timbre próprio, responda CONTINUACAO.
                - ATENÇÃO (anexos de TFD com timbre próprio): um pedido de TFD/RS quase sempre vem acompanhado de um
                  LAUDO MÉDICO emitido pela Secretaria de Saúde do estado de ORIGEM do paciente (o estado onde ele
                  mora, diferente do RS), justificando o procedimento — com cabeçalho/timbre institucional próprio
                  desse outro estado. Mesmo tendo timbre próprio, esse laudo é um ANEXO do pedido de TFD/RS, não uma
                  nova solicitação independente — responda CONTINUACAO para ele (mesmo que a categoria dele isolada
                  seja TFD_OUTROS_ESTADOS). Só responda INICIO para uma página de TFD quando ela for, ela mesma, o
                  formulário de CADASTRO do RS (título "Solicitação de cadastro para consulta -TFD") ou não tiver
                  nenhuma relação de conteúdo (paciente, procedimento) com um pedido de TFD que já vinha sendo
                  descrito nas páginas anteriores.

                Categorias:
                - TFD_RS: documento de Tratamento Fora de Domicílio (TFD) especificamente do Rio Grande do Sul. A
                  página de capa é um formulário fixo com o cabeçalho "ESTADO DO RIO GRANDE DO SUL", "SECRETARIA
                  ESTADUAL DE SAÚDE", "DEPARTAMENTO DE REGULAÇÃO ESTADUAL" e "CENTRAL ESTADUAL DE TRANSPLANTES", com o
                  título "Solicitação de cadastro para consulta -TFD". Só classifique como TFD_RS se o texto indicar
                  claramente que é do Rio Grande do Sul (RS).
                - TFD_OUTROS_ESTADOS: documento de Tratamento Fora de Domicílio (TFD) igual ao TFD_RS em estrutura e
                  finalidade, mas de QUALQUER OUTRO estado que não seja o Rio Grande do Sul (ou quando o texto fala
                  de TFD/tratamento fora de domicílio sem indicar claramente que é do RS).
                - PROTOCOLO_ENCAMINHAMENTO: protocolo de encaminhamento entre unidades/serviços de saúde.
                - EXAMES: pedidos, laudos ou resultados de exames médicos (sangue, imagem, etc).
                - DOCUMENTOS: documentos pessoais e comprobatórios em geral — RG, CPF, identidade, certidões,
                  comprovante de residência, declarações, procurações, carteira/cartão do SUS e encaminhamento
                  médico comum (sem o formato de protocolo de encaminhamento entre unidades).
                - OUTROS: qualquer outro conteúdo que não se encaixe nas categorias acima.

                Texto da página:
                ---
                %s
                ---

                A categoria deve ser uma destas: TFD_RS, TFD_OUTROS_ESTADOS, PROTOCOLO_ENCAMINHAMENTO, EXAMES, DOCUMENTOS, OUTROS
                Exemplos de resposta válida: TFD_RS|INICIO
                TFD_RS|CONTINUACAO
                EXAMES|INICIO
                """.formatted(textoPagina == null ? "" : textoPagina.trim());
    }

    /**
     * Interpreta a resposta no formato {@code CATEGORIA|INICIO} /
     * {@code CATEGORIA|CONTINUACAO}. Continua aceitando respostas antigas só
     * com a categoria (nesse caso o sinal de início/continuação fica nulo).
     */
    private ClassificacaoIa interpretarResposta(String texto) {
        if (texto == null) {
            return null;
        }
        // Espaco/hifen viram "_" ANTES de descartar o resto, senao uma resposta
        // como "TFD OUTROS ESTADOS|INICIO" perde os espacos e vira
        // "TFDOUTROSESTADOS", que nao bate com nenhum nome de categoria (o
        // enum usa "_") e a resposta inteira era descartada a toa.
        String limpo = texto.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "_")
                .replaceAll("[^A-Z_|]", "");

        String parteCategoria = limpo;
        // Sem o separador, procura o marcador no texto inteiro (nenhum nome de
        // categoria contém "INICIO"/"CONTINUACAO", então não há ambiguidade).
        String parteInicio = limpo;
        int separador = limpo.indexOf('|');
        if (separador >= 0) {
            parteCategoria = limpo.substring(0, separador);
            parteInicio = limpo.substring(separador + 1);
        }

        Boolean inicioDocumento = null;
        if (parteInicio.contains("CONTINUACAO")) {
            inicioDocumento = Boolean.FALSE;
        } else if (parteInicio.contains("INICIO")) {
            inicioDocumento = Boolean.TRUE;
        }

        for (Categoria categoria : Categoria.values()) {
            if (parteCategoria.contains(categoria.name())) {
                return new ClassificacaoIa(categoria, inicioDocumento);
            }
        }
        LOG.warnf("Resposta do Gemini não reconhecida como categoria: '%s'", texto);
        return null;
    }

    /**
     * Modo CONTEXTO: em vez de classificar uma página isolada, manda uma janela de páginas de uma vez (mais
     * páginas anteriores só como contexto, não reclassificadas) e pede ao Gemini para AGRUPAR as páginas em
     * documentos — resolve na raiz o problema de um anexo com timbre próprio (ex.: laudo médico de outro estado
     * dentro de um pedido de TFD/RS) ser confundido com o início de um documento novo, porque o modelo agora vê
     * várias páginas em sequência, não uma por vez. Retorna {@code null} se todas as tentativas falharem — o
     * chamador ({@code AgrupadorContextualService}) decide o fallback (por janela, não mais por página).
     */
    public ResultadoAgrupamento agrupar(List<String> textosContexto, int primeiraPaginaContexto,
            List<String> textosDecisao, int primeiraPaginaDecisao) {
        if (!disponivel()) {
            return null;
        }
        int ultimaPaginaDecisao = primeiraPaginaDecisao + textosDecisao.size() - 1;
        String prompt = montarPromptAgrupamento(textosContexto, primeiraPaginaContexto, textosDecisao, primeiraPaginaDecisao);
        // Piso alto o bastante pra sobrar espaço de saída mesmo com thinkingBudget=0 (ver GeminiRequest.ThinkingConfig);
        // o valor antigo (40*n+256) já causou resposta vazia/truncada em janelas maiores sem gerar nenhuma exceção.
        int maxOutputTokens = Math.min(8192, 60 * textosDecisao.size() + 2048);
        List<String> categoriasValidas = Arrays.stream(Categoria.values()).map(Enum::name).toList();

        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            try {
                GeminiResponse resposta = client.gerarConteudo(config.modelo(), config.apiKey(),
                        GeminiRequest.deJsonAgrupamento(prompt, maxOutputTokens, categoriasValidas));
                String texto = resposta.primeiroTexto();
                if (texto.isBlank()) {
                    // Sem exceção nenhuma (a chamada HTTP teve sucesso) - mas sem conteúdo pra parsear. Acontece
                    // sobretudo quando o modelo esgota maxOutputTokens "pensando" (finishReason=MAX_TOKENS) antes
                    // de emitir o JSON; sem logar isso, esse caso ficava indistinguível de qualquer outra causa de
                    // fallback (silêncio total no log, ao contrário de toda falha de rede/HTTP).
                    LOG.warnf("Gemini devolveu resposta vazia para a janela de páginas %d-%d (finishReason=%s) - "
                            + "tratando como não resolvida", primeiraPaginaDecisao, ultimaPaginaDecisao,
                            resposta.primeiroFinishReason());
                    return null;
                }
                return InterpretadorAgrupamento.interpretar(texto, primeiraPaginaDecisao, ultimaPaginaDecisao);
            } catch (Exception e) {
                boolean permanente = erroPermanente(e);
                if (permanente || tentativa == MAX_TENTATIVAS) {
                    LOG.warnf("Falha ao agrupar janela de páginas %d-%d com Gemini (%s%s) - usando fallback por "
                            + "palavra-chave", primeiraPaginaDecisao, ultimaPaginaDecisao, detalheErro(e),
                            permanente ? ", erro não é passageiro, não tentando de novo"
                                    : " após " + MAX_TENTATIVAS + " tentativa(s)");
                    return null;
                }
                LOG.debug("Falha ao agrupar janela com Gemini, tentando novamente", e);
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private String montarPromptAgrupamento(List<String> textosContexto, int primeiraPaginaContexto,
            List<String> textosDecisao, int primeiraPaginaDecisao) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                Você vai analisar páginas de texto extraídas de um PDF (uma digitalização de vários documentos
                médicos/administrativos juntos) e AGRUPAR as páginas para CLASSIFICAR em DOCUMENTOS: sequências de
                páginas consecutivas que formam UM MESMO pedido/documento físico (mesmo paciente, mesmo
                procedimento, mesma sequência lógica), dizendo a categoria de cada documento.

                """);

        if (!textosContexto.isEmpty()) {
            sb.append("Páginas de CONTEXTO abaixo (já classificadas antes — NÃO responda uma linha para elas, ")
                    .append("servem só para você entender se a 1ª página a classificar continua algum documento ")
                    .append("que já vinha sendo descrito):\n\n");
            int pagina = primeiraPaginaContexto;
            for (String texto : textosContexto) {
                sb.append("--- página ").append(pagina).append(" (contexto) ---\n")
                        .append(texto == null ? "" : texto.trim()).append('\n');
                pagina++;
            }
            sb.append('\n');
        }

        sb.append("PÁGINAS PARA CLASSIFICAR (responda uma linha para CADA uma destas, na ordem):\n\n");
        int pagina = primeiraPaginaDecisao;
        for (String texto : textosDecisao) {
            sb.append("--- página ").append(pagina).append(" ---\n")
                    .append(texto == null ? "" : texto.trim()).append('\n');
            pagina++;
        }

        sb.append("""

                Responda com um item por página a classificar (nos campos "pagina", "documento" e "categoria" do
                schema JSON configurado), na mesma ordem em que as páginas foram listadas acima.

                Regras de agrupamento:
                - Páginas do MESMO documento físico recebem o MESMO numero_do_documento; documentos diferentes têm
                  números diferentes. Numere localmente a partir de 1, na ordem em que os documentos aparecem entre
                  as páginas A CLASSIFICAR (ignore qualquer numeração das páginas de contexto). Números de documento
                  devem ser usados em sequência CONTÍNUA de páginas — nunca "reabra" um número já fechado.
                - Um documento de TFD (RS ou de outro estado) pode ter só 1 página ou várias (capa + laudo médico +
                  exames + documentos pessoais anexados) — julgue pelo CONTEÚDO (mesmo paciente, mesmo procedimento),
                  nunca por um número fixo de páginas.
                - Documentos, exames e protocolos avulsos (fora de um pedido de TFD) geralmente têm 1 página cada,
                  mas podem ter mais se o conteúdo continuar claramente na página seguinte (ex.: exame de várias
                  folhas, ou a 1ª página de decisão sendo continuação de um documento aberto no contexto).

                IMPORTANTE sobre TFD — leia com atenção, é o erro mais comum: um pedido de TFD/RS quase sempre vem
                acompanhado de um LAUDO MÉDICO emitido pela Secretaria de Saúde do estado de ORIGEM do paciente
                (diferente do RS), com timbre/título institucional PRÓPRIO desse outro estado, justificando o
                procedimento. Mesmo tendo timbre próprio, esse laudo é um ANEXO do MESMO pedido de TFD/RS que veio
                antes — dê a ele o MESMO numero_do_documento da capa do RS, não abra um documento novo para ele,
                mesmo que olhando SÓ para aquela página ela pareça um documento de TFD_OUTROS_ESTADOS independente.

                IMPORTANTE sobre páginas quase em branco: um pedido de TFD/RS também costuma vir acompanhado de
                anexos ESCANEADOS sem OCR (exames, laudos em imagem) — o texto extraído dessas páginas pode ser
                quase nada, às vezes só um carimbo de sistema de protocolo eletrônico (com URL de validação,
                código hexadecimal, nome de quem fez o download). Ausência de conteúdo NÃO é evidência de nada —
                NUNCA escolha TFD_OUTROS_ESTADOS (ou qualquer categoria) só porque a página está "quase vazia". Se
                a página anterior fazia parte de um documento aberto, dê a ela o MESMO numero_do_documento e a
                MESMA categoria desse documento; só abra um documento novo se a própria página tiver conteúdo
                claro que justifique isso.

                Categorias possíveis (a categoria vale para o DOCUMENTO inteiro, decidida pela página que melhor o
                identifica, normalmente a capa):
                - TFD_RS: pedido de Tratamento Fora de Domicílio (TFD) do Rio Grande do Sul. Em ALGUMA página do
                  documento aparece claramente um marcador do RS: "ESTADO DO RIO GRANDE DO SUL", "SECRETARIA
                  ESTADUAL DE SAÚDE", "DEPARTAMENTO DE REGULAÇÃO ESTADUAL", "CENTRAL ESTADUAL DE TRANSPLANTES", ou o
                  título "Solicitação de cadastro para consulta -TFD". Sem esse marcador claro, NÃO é TFD_RS mesmo
                  que fale de TFD.
                - TFD_OUTROS_ESTADOS: pedido de TFD igual em estrutura e finalidade, mas que MOSTRA claramente ser
                  de outro estado (ou pelo menos mostra conteúdo real de um pedido de TFD) SEM nenhum marcador do
                  RS. A mera ausência de texto/conteúdo numa página NÃO é motivo para esta categoria.
                - PROTOCOLO_ENCAMINHAMENTO: protocolo de encaminhamento entre unidades/serviços de saúde (não
                  confundir com um encaminhamento médico comum, que é DOCUMENTOS).
                - EXAMES: pedidos, laudos ou resultados de exames médicos avulsos (sangue, imagem, etc.) — que NÃO
                  fazem parte de um pedido de TFD.
                - DOCUMENTOS: documentos pessoais e comprobatórios em geral — RG, CPF, identidade, certidões,
                  comprovante de residência, declarações, procurações, carteira/cartão do SUS, encaminhamento
                  médico comum.
                - OUTROS: qualquer outro conteúdo que não se encaixe nas categorias acima.

                Exemplo de agrupamento para uma janela de 5 páginas a classificar, onde as 3 primeiras são o mesmo
                pedido de TFD/RS (capa do RS + laudo médico de outro estado anexado + um exame anexado) e as 2
                últimas são exames avulsos e distintos entre si: páginas 1, 2 e 3 recebem documento=1 e
                categoria=TFD_RS; página 4 recebe documento=2 e categoria=EXAMES; página 5 recebe documento=3 e
                categoria=EXAMES.
                """);

        return sb.toString();
    }
}

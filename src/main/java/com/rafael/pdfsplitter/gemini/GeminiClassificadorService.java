package com.rafael.pdfsplitter.gemini;

import java.util.List;
import java.util.Locale;

import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import com.rafael.pdfsplitter.Categoria;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
                if (tentativa == MAX_TENTATIVAS) {
                    LOG.warn("Falha ao classificar página com Gemini após " + MAX_TENTATIVAS
                            + " tentativa(s), usando fallback por palavra-chave", e);
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
        int maxOutputTokens = Math.min(8192, 24 * textosDecisao.size() + 256);

        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            try {
                GeminiResponse resposta = client.gerarConteudo(config.modelo(), config.apiKey(),
                        GeminiRequest.deTexto(prompt, maxOutputTokens));
                return InterpretadorAgrupamento.interpretar(resposta.primeiroTexto(), primeiraPaginaDecisao, ultimaPaginaDecisao);
            } catch (Exception e) {
                if (tentativa == MAX_TENTATIVAS) {
                    LOG.warn("Falha ao agrupar janela de páginas " + primeiraPaginaDecisao + "-" + ultimaPaginaDecisao
                            + " com Gemini após " + MAX_TENTATIVAS + " tentativa(s), usando fallback por palavra-chave", e);
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

                Responda no formato abaixo, uma linha por página a classificar (nada além disso — sem explicações,
                sem markdown, sem cabeçalho):

                numero_da_pagina|numero_do_documento|CATEGORIA

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

                Categorias possíveis (a categoria vale para o DOCUMENTO inteiro, decidida pela página que melhor o
                identifica, normalmente a capa):
                - TFD_RS: pedido de Tratamento Fora de Domicílio (TFD) do Rio Grande do Sul. Em ALGUMA página do
                  documento aparece claramente um marcador do RS: "ESTADO DO RIO GRANDE DO SUL", "SECRETARIA
                  ESTADUAL DE SAÚDE", "DEPARTAMENTO DE REGULAÇÃO ESTADUAL", "CENTRAL ESTADUAL DE TRANSPLANTES", ou o
                  título "Solicitação de cadastro para consulta -TFD". Sem esse marcador claro, NÃO é TFD_RS mesmo
                  que fale de TFD.
                - TFD_OUTROS_ESTADOS: pedido de TFD igual em estrutura e finalidade, mas SEM nenhum marcador do RS
                  (de outro estado, ou sem indicação clara de estado).
                - PROTOCOLO_ENCAMINHAMENTO: protocolo de encaminhamento entre unidades/serviços de saúde (não
                  confundir com um encaminhamento médico comum, que é DOCUMENTOS).
                - EXAMES: pedidos, laudos ou resultados de exames médicos avulsos (sangue, imagem, etc.) — que NÃO
                  fazem parte de um pedido de TFD.
                - DOCUMENTOS: documentos pessoais e comprobatórios em geral — RG, CPF, identidade, certidões,
                  comprovante de residência, declarações, procurações, carteira/cartão do SUS, encaminhamento
                  médico comum.
                - OUTROS: qualquer outro conteúdo que não se encaixe nas categorias acima.

                Exemplo de resposta válida para uma janela de 5 páginas a classificar, onde as 3 primeiras são o
                mesmo pedido de TFD/RS (capa do RS + laudo médico de outro estado anexado + um exame anexado) e as
                2 últimas são exames avulsos e distintos entre si:
                1|1|TFD_RS
                2|1|TFD_RS
                3|1|TFD_RS
                4|2|EXAMES
                5|3|EXAMES
                """);

        return sb.toString();
    }
}

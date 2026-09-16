package com.rafael.pdfsplitter.gemini;

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

    /**
     * Pede ao Gemini para classificar o texto de uma página em uma das
     * categorias suportadas e, junto, dizer se a página é o INÍCIO de um
     * documento ou a CONTINUAÇÃO do documento anterior. Retorna null se a
     * chamada falhar (ex: sem rede, quota, resposta inesperada) para que o
     * chamador use o fallback por palavra-chave.
     */
    public ClassificacaoIa classificar(String textoPagina) {
        if (!disponivel()) {
            return null;
        }
        try {
            String prompt = montarPrompt(textoPagina);
            GeminiResponse resposta = client.gerarConteudo(config.modelo(), config.apiKey(), GeminiRequest.deTexto(prompt));
            return interpretarResposta(resposta.primeiroTexto());
        } catch (Exception e) {
            LOG.warn("Falha ao classificar página com Gemini, usando fallback por palavra-chave", e);
            return null;
        }
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
        String limpo = texto.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z_|]", "");

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
}

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
     * categorias suportadas. Retorna null se a chamada falhar (ex: sem rede,
     * quota, resposta inesperada) para que o chamador use o fallback por
     * palavra-chave.
     */
    public Categoria classificar(String textoPagina) {
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
                Classifique o texto de UMA página de um PDF em exatamente uma das categorias abaixo.
                Responda APENAS com o identificador da categoria (uma palavra), sem explicações.

                Categorias:
                - TFD_RS: documento de Tratamento Fora de Domicílio (TFD/RS), autorização de viagem, central de regulação.
                - PROTOCOLO_ENCAMINHAMENTO: protocolo de encaminhamento entre unidades/serviços de saúde.
                - EXAMES: pedidos, laudos ou resultados de exames médicos (sangue, imagem, etc).
                - DOCUMENTOS: documentos pessoais (RG, CPF, certidões) e comprovante de residência.
                - OUTROS: qualquer outro conteúdo que não se encaixe nas categorias acima.

                Texto da página:
                ---
                %s
                ---

                Responda só com uma destas palavras: TFD_RS, PROTOCOLO_ENCAMINHAMENTO, EXAMES, DOCUMENTOS, OUTROS
                """.formatted(textoPagina == null ? "" : textoPagina.trim());
    }

    private Categoria interpretarResposta(String texto) {
        if (texto == null) {
            return null;
        }
        String limpo = texto.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z_]", "");
        for (Categoria categoria : Categoria.values()) {
            if (limpo.contains(categoria.name())) {
                return categoria;
            }
        }
        LOG.warnf("Resposta do Gemini não reconhecida como categoria: '%s'", texto);
        return null;
    }
}

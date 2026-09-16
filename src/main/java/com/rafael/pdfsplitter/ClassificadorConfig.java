package com.rafael.pdfsplitter;

import java.util.List;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Palavras-chave (sem acento, minúsculas) usadas para classificar cada página.
 * A ordem de verificação é TFD_RS -> TFD_OUTROS_ESTADOS -> PROTOCOLO_ENCAMINHAMENTO
 * -> EXAMES -> DOCUMENTOS -> OUTROS (fallback), então mantenha os termos mais
 * específicos nas categorias verificadas primeiro.
 */
@ConfigMapping(prefix = "classificador")
public interface ClassificadorConfig {

    /** Termos que só aparecem no formulário de TFD do Rio Grande do Sul (RS). */
    List<String> tfdRs();

    /** Termos genéricos de TFD que não identificam o Rio Grande do Sul (outros estados). */
    List<String> tfdOutrosEstados();

    List<String> protocoloEncaminhamento();

    List<String> exames();

    List<String> documentos();

    /**
     * Quantidade de páginas que compõem um documento de TFD (RS ou de outro
     * estado). Normalmente só a primeira página do bloco tem as palavras-chave
     * (as seguintes são continuação sem texto identificável), então ao
     * detectar o gatilho as próximas páginas até completar esse total também
     * entram no mesmo bloco/categoria.
     */
    @WithDefault("3")
    int tfdRsPaginasPorBloco();

    /**
     * Termos FORTES (de cabeçalho/timbre/título de formulário) que indicam que
     * uma página é a CAPA de um novo pedido de TFD — de qualquer estado.
     *
     * Usados só para decidir se a extensão do bloco de TFD deve parar naquela
     * página, nunca para classificar. É preciso uma lista separada porque
     * {@link #tfdOutrosEstados()} é propositalmente genérica ("tfd",
     * "tratamento fora de domicilio"...) e esses termos aparecem no texto
     * corrido das páginas de CONTINUAÇÃO do próprio formulário do RS, o que
     * fazia o bloco ser cortado no meio.
     */
    List<String> tfdCabecalhoNovoDocumento();

    /**
     * Quando {@code true} (padrão), a extensão de um bloco de TFD só para numa
     * página se ela realmente parecer a capa de um novo pedido — ou seja, se o
     * Gemini disse que é INÍCIO de documento, ou se o texto bate em
     * {@link #tfdCabecalhoNovoDocumento()}. Quando {@code false}, volta ao
     * comportamento antigo (qualquer página classificada como TFD corta o
     * bloco), que perdia páginas de continuação.
     */
    @WithDefault("true")
    boolean tfdExigirCabecalhoParaQuebrarBloco();
}

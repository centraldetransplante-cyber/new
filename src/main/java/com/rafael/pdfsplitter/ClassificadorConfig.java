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

    /**
     * Termos que indicam Rio Grande do Sul (RS) — órgão emissor, secretaria,
     * "tfd/rs" etc. SOZINHOS não bastam para virar TFD_RS (ex.: "central
     * estadual de transplantes" também aparece em ofícios/laudos que nada têm
     * a ver com TFD); precisam aparecer JUNTO com um termo de
     * {@link #tfdTermoGenerico()} na mesma página. Ver {@code classificador.tfd-rs-marcador}.
     */
    List<String> tfdRsMarcador();

    /**
     * Termos que indicam, de forma genérica, que a página é sobre TFD (sem
     * indicar de qual estado). Página que bate aqui E em
     * {@link #tfdRsMarcador()} é TFD_RS; que bate só aqui é TFD_OUTROS_ESTADOS;
     * que bate só no marcador do RS (sem termo de TFD) NÃO é TFD.
     */
    List<String> tfdTermoGenerico();

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

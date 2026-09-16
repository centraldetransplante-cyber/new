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
}

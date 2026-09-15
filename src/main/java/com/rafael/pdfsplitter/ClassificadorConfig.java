package com.rafael.pdfsplitter;

import java.util.List;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Palavras-chave (sem acento, minúsculas) usadas para classificar cada página.
 * A ordem de verificação é TFD_RS -> EXAMES -> DOCUMENTOS -> OUTROS (fallback),
 * então mantenha os termos mais específicos nas categorias verificadas primeiro.
 */
@ConfigMapping(prefix = "classificador")
public interface ClassificadorConfig {

    List<String> tfdRs();

    List<String> protocoloEncaminhamento();

    List<String> exames();

    List<String> documentos();

    /**
     * Quantidade de páginas que compõem um documento TFD/RS. Normalmente só a
     * primeira página do bloco tem as palavras-chave (as seguintes são
     * continuação sem texto identificável), então ao detectar o gatilho as
     * próximas páginas até completar esse total também viram TFD/RS.
     */
    @WithDefault("3")
    int tfdRsPaginasPorBloco();
}

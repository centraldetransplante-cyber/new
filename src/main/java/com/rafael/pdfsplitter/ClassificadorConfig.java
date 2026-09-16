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
     * {@link #tfdTermoGenerico()} é propositalmente genérica ("tfd",
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
     *
     * NOTA: as 3 configs acima (paginas-por-bloco, cabecalho-novo-documento,
     * exigir-cabecalho) só valem no modo {@code PAGINA} ou quando o Gemini está
     * indisponível no modo {@code CONTEXTO} — nesse modo o agrupamento em
     * documentos vem direto do próprio Gemini, lendo várias páginas de uma vez.
     */
    @WithDefault("true")
    boolean tfdExigirCabecalhoParaQuebrarBloco();

    /**
     * {@code CONTEXTO} (padrão): o Gemini recebe várias páginas de uma vez
     * (com contexto das páginas anteriores) e agrupa diretamente em
     * documentos, resolvendo casos como um anexo de outro estado dentro de um
     * pedido de TFD/RS sem precisar de heurística de bloco no Java.
     * {@code PAGINA}: comportamento antigo, classifica página por página e
     * tenta reconstruir blocos de TFD depois — mantido como "botão de
     * pânico" (trocar essa env var no Render volta ao comportamento anterior
     * sem precisar reimplantar código).
     */
    @WithDefault("CONTEXTO")
    String modo();

    /** Tamanho da janela de páginas enviada de uma vez ao Gemini no modo CONTEXTO. */
    @WithDefault("10")
    int contextoPaginasPorJanela();

    /**
     * Quantas páginas ANTERIORES à janela são reenviadas só como contexto
     * (não classificadas de novo) para o Gemini decidir se a 1ª página da
     * janela continua um documento que já vinha sendo descrito.
     */
    @WithDefault("4")
    int contextoPaginasDeContexto();

    /** Trunca o texto de cada página nesse tanto de caracteres antes de montar o prompt (OCR ruim pode gerar lixo). */
    @WithDefault("2500")
    int contextoMaxCaracteresPorPagina();

    /**
     * Tamanho máximo (em MB) de cada PDF de categoria gerado. Se o PDF de uma categoria (ex.: exames.pdf, com
     * muitas imagens de exame escaneadas) sair maior que isso, as imagens dele são recomprimidas (qualidade JPEG
     * reduzida em etapas) até caber no limite — ver {@code PdfSplitService.comprimirSePreciso}.
     */
    @WithDefault("10")
    int tamanhoMaximoArquivoMb();
}

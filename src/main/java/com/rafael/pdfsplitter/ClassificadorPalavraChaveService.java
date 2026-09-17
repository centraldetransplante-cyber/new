package com.rafael.pdfsplitter;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Classificação por palavra-chave — o fallback usado quando o Gemini está indisponível (nos dois modos,
 * {@code PAGINA} e {@code CONTEXTO}) e, no modo {@code PAGINA}, também a "rede de segurança" para quando o Gemini
 * responde OUTROS. Extraído para uma classe própria porque tanto {@link PdfSplitService} (modo PAGINA e fallback
 * geral) quanto {@link AgrupadorContextualService} (fallback por janela e a validação de TFD_RS) precisam dele.
 */
@ApplicationScoped
public class ClassificadorPalavraChaveService {

    private final ClassificadorConfig config;

    public ClassificadorPalavraChaveService(ClassificadorConfig config) {
        this.config = config;
    }

    /**
     * TFD_RS x TFD_OUTROS_ESTADOS exige as DUAS coisas na mesma página: um
     * termo genérico de TFD ({@link ClassificadorConfig#tfdTermoGenerico()})
     * E um marcador do RS ({@link ClassificadorConfig#tfdRsMarcador()}).
     * Marcador do RS sozinho (ex.: "central estadual de transplantes" numa
     * página que não é sobre TFD, comum aqui já que é uma central de
     * transplantes) NÃO classifica como TFD — evita falso positivo.
     */
    public Categoria classificar(String textoNormalizado) {
        boolean tfdGenerico = contemAlgumaPalavra(textoNormalizado, config.tfdTermoGenerico());
        boolean marcadorRs = contemAlgumaPalavra(textoNormalizado, config.tfdRsMarcador());
        if (tfdGenerico && marcadorRs) {
            return Categoria.TFD_RS;
        }
        if (tfdGenerico) {
            return Categoria.TFD_OUTROS_ESTADOS;
        }
        if (contemAlgumaPalavra(textoNormalizado, config.protocoloEncaminhamento())) {
            return Categoria.PROTOCOLO_ENCAMINHAMENTO;
        }
        if (contemAlgumaPalavra(textoNormalizado, config.exames())) {
            return Categoria.EXAMES;
        }
        if (contemAlgumaPalavra(textoNormalizado, config.documentos())) {
            return Categoria.DOCUMENTOS;
        }
        return Categoria.OUTROS;
    }

    /**
     * Palavras-chave muito curtas (ex.: "rg") não usam contains puro, senão
     * batem dentro de qualquer palavra que contenha essas letras em sequência
     * ("urgente", "orgao", "cirurgia", "energia"...) — usa-se \b (borda de
     * palavra) só para essas. Palavras mais longas continuam com contains,
     * que já tolera variações de pontuação/plural ao redor.
     */
    public boolean contemAlgumaPalavra(String textoNormalizado, List<String> palavrasChave) {
        for (String palavra : palavrasChave) {
            String normalizada = normalizar(palavra);
            if (normalizada.length() <= 3) {
                if (Pattern.compile("\\b" + Pattern.quote(normalizada) + "\\b").matcher(textoNormalizado).find()) {
                    return true;
                }
            } else if (textoNormalizado.contains(normalizada)) {
                return true;
            }
        }
        return false;
    }

    public String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return semAcento.toLowerCase();
    }

    private static final Pattern LETRAS = Pattern.compile("[a-z]");

    /**
     * Uma página "pobre" tem, na prática, conteúdo zero: depois de remover as frases de carimbo/boilerplate de
     * {@link ClassificadorConfig#contextoCarimboProtocoloPadroes()} (carimbo de protocolo eletrônico, assinatura
     * de download, validação de autenticidade — presentes em toda página de um processo digital, RS ou de
     * qualquer outro estado, e que por isso não dizem nada sobre a categoria do documento), sobram menos letras do
     * que {@link ClassificadorConfig#contextoMinCaracteresConteudoUtil()}.
     *
     * Não é o mesmo que "sem texto" ({@code isBlank()}): uma página só com o carimbo TEM texto (às vezes 200+
     * caracteres, incluindo o código de validação em hexadecimal), mas não tem nenhum sinal de categoria — é
     * exatamente esse tipo de página, comum em anexos escaneados sem OCR, que causou um pedido de TFD/RS real
     * saindo com anexos em {@code tfd_outros_estados.pdf} (ver AgrupadorContextualService e CLAUDE.md).
     */
    public boolean paginaPobre(String textoNormalizado) {
        if (textoNormalizado == null || textoNormalizado.isBlank()) {
            return true;
        }
        String semCarimbo = textoNormalizado;
        for (String padrao : config.contextoCarimboProtocoloPadroes()) {
            semCarimbo = semCarimbo.replace(normalizar(padrao), " ");
        }
        long letras = LETRAS.matcher(semCarimbo).results().count();
        return letras < config.contextoMinCaracteresConteudoUtil();
    }
}

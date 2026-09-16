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
}

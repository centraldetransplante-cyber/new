package com.rafael.pdfsplitter.gemini;

import com.rafael.pdfsplitter.Categoria;

/**
 * Resposta do Gemini para uma página: a categoria e, quando o modelo
 * conseguiu responder no formato pedido, se a página é o INÍCIO de um
 * documento (tem cabeçalho/timbre/título de formulário) ou a CONTINUAÇÃO do
 * documento da página anterior.
 *
 * @param categoria       categoria da página (nunca nula).
 * @param inicioDocumento {@code true} = início de documento, {@code false} =
 *                        continuação, {@code null} = o modelo não informou.
 */
public record ClassificacaoIa(Categoria categoria, Boolean inicioDocumento) {
}

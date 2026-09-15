package com.rafael.pdfsplitter.gemini;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

@RegisterRestClient(configKey = "gemini-api")
public interface GeminiClient {

    @POST
    @Path("/v1beta/models/{model}:generateContent")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    GeminiResponse gerarConteudo(
            @PathParam("model") String model,
            @QueryParam("key") String apiKey,
            GeminiRequest request);
}

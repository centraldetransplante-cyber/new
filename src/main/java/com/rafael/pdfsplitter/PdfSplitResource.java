package com.rafael.pdfsplitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.PartType;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/pdf-splitter")
public class PdfSplitResource {

    private static final Logger LOG = Logger.getLogger(PdfSplitResource.class);

    private final PdfSplitService service;

    public PdfSplitResource(PdfSplitService service) {
        this.service = service;
    }

    public static class Formulario {
        @RestForm("file")
        @PartType(MediaType.APPLICATION_OCTET_STREAM)
        public FileUpload file;
    }

    @POST
    @Path("/split")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces("application/zip")
    public Response separar(Formulario formulario) {
        if (formulario.file == null) {
            return erro(Response.Status.BAD_REQUEST, "Envie o PDF no campo 'file' (multipart/form-data).");
        }

        try {
            ResultadoSeparacao resultado = service.separar(formulario.file.uploadedFile().toFile());
            String relatorioBase64 = Base64.getEncoder()
                    .encodeToString(resultado.relatorioJson().getBytes(StandardCharsets.UTF_8));

            return Response.ok(resultado.zip())
                    .header("Content-Disposition", "attachment; filename=\"documentos-separados.zip\"")
                    .header("X-Relatorio-Classificacao", relatorioBase64)
                    .header("Access-Control-Expose-Headers", "X-Relatorio-Classificacao")
                    .build();
        } catch (PdfInvalidoException e) {
            return erro(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            LOG.error("Falha ao processar PDF enviado", e);
            return erro(Response.Status.INTERNAL_SERVER_ERROR,
                    "Não foi possível processar o PDF. Tente novamente em instantes.");
        } catch (Exception e) {
            LOG.error("Erro inesperado ao separar PDF", e);
            return erro(Response.Status.INTERNAL_SERVER_ERROR,
                    "Erro inesperado ao processar o PDF. Tente novamente em instantes.");
        }
    }

    private Response erro(Response.Status status, String mensagem) {
        return Response.status(status).entity(mensagem).type(MediaType.TEXT_PLAIN).build();
    }
}

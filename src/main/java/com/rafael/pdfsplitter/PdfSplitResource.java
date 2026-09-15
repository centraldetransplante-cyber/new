package com.rafael.pdfsplitter;

import java.io.IOException;
import java.io.InputStream;

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
    public Response separar(Formulario formulario) throws IOException {
        if (formulario.file == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Envie o PDF no campo 'file' (multipart/form-data).")
                    .build();
        }

        try (InputStream entrada = java.nio.file.Files.newInputStream(formulario.file.uploadedFile())) {
            byte[] zip = service.separarEmZip(entrada);
            return Response.ok(zip)
                    .header("Content-Disposition", "attachment; filename=\"documentos-separados.zip\"")
                    .build();
        }
    }
}

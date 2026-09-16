# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Quarkus REST app that receives one combined/scanned PDF (many documents merged together) and splits it into
category PDFs: `documentos`, `exames`, `tfd_rs`, `tfd_outros_estados`, `protocolo_encaminhamento`, `outros`. Each
category becomes ONE PDF containing all of that category's pages, merged in original order — not one file per
physical sub-document. A single-page static UI (`src/main/resources/META-INF/resources/index.html`) uploads the
PDF and downloads the resulting zip.

Deployed at https://pdf-splitter-0xlu.onrender.com (Render free tier, auto-sleeps after inactivity — first request
after idle can 502/be slow while it wakes up). Source pushed to
https://github.com/centraldetransplante-cyber/new (this repo's remote `origin`, branch `main`). Render project/
account belongs to `rafael707375@gmail.com`.

## Commands

```shell
./mvnw compile quarkus:dev      # run locally with live reload, http://localhost:8080
./mvnw -q compile               # compile only, quiet output — use this to check for errors after edits
./mvnw package                  # build target/quarkus-app/
```

No test suite exists in this project. There is no linter configured.

Quarkus dev mode recompiles Java sources and reloads `application.properties`/`index.html` automatically on the
next HTTP request — you don't need to restart the dev server after an edit, just curl it again. A background
dev server is often already running on port 8080 during a session; check with `netstat -ano | grep LISTENING | grep :8080`
before starting a new one.

### Deploying

There's no CI/CD wired up. Deploys are triggered manually via the Render API (no GitHub App is installed on the
`centraldetransplante-cyber` org, so push-triggered auto-deploy does not fire):

```shell
git push                                                    # push to origin/main first
curl -X POST -H "Authorization: Bearer $RENDER_API_KEY" \
  -H "Content-Type: application/json" -d '{}' \
  "https://api.render.com/v1/services/srv-dakpbkajnfac73bfsrkg/deploys"
```

Poll `GET .../deploys/{id}` for `status` (`build_in_progress` → `live` or `build_failed`). Build/runtime logs:
`GET https://api.render.com/v1/logs?ownerId={ownerId}&resource=srv-dakpbkajnfac73bfsrkg&limit=100`.

The root `Dockerfile` (not `src/main/docker/Dockerfile.jvm`, which is the Quarkus-generated one and expects a
pre-built `target/` that Render's build never has) is a multi-stage build that compiles from source. Render sets
`PORT`; `application.properties` binds to it via `quarkus.http.port=${PORT:8080}` and `quarkus.http.host=0.0.0.0`.

`GEMINI_API_KEY` is a Render env var (not in the repo). Locally it comes from a `.env` file (gitignored) — the app
itself doesn't auto-load `.env`; export it into the shell before running `quarkus:dev` if you need Gemini to work
locally instead of falling back to keywords.

## Architecture: the classification pipeline

`PdfSplitResource.split()` → `PdfSplitService.separar()` does, per uploaded PDF:

1. **Per-page classification** (`classificarPaginas`): extracts each page's text with PDFBox, then classifies it
   via `classificarTexto` — tries Gemini first (`GeminiClassificadorService`), falls back to substring keyword
   matching (`classificarPorPalavraChave`, config lists in `application.properties`) if Gemini is unavailable, times
   out, or errors. Every page's `Categoria` + which method decided it (`MetodoClassificacao`: `GEMINI`,
   `PALAVRA_CHAVE`, or `REGRA_BLOCO_TFD`) is tracked in `PaginaClassificada`, plus the page's normalized text and
   (when Gemini answered) whether Gemini thinks the page is the *start* of a document vs a *continuation* of the
   previous one (`ClassificacaoIa.inicioDocumento`).

2. **TFD block extension** (`estenderBlocosTfd`): the trickiest part of this codebase. A TFD (Tratamento Fora de
   Domicílio) request is a bundle of several consecutive pages — a cover form plus attachments (checklists, ID
   copies, exam results) — but usually only the cover page carries the keywords/header that make it classifiable.
   When a `TFD_RS` or `TFD_OUTROS_ESTADOS` trigger page is found, the next `classificador.tfd-rs-paginas-por-bloco`
   pages (default 3 total, i.e. 2 more after the trigger) are pulled into that same category, *overriding* whatever
   they classified as on their own — **unless** one of them is itself judged to be the cover of *another* new TFD
   request, via `iniciaNovoPedidoTfd`.

   That "is this really a new cover page" check deliberately does NOT reuse the same keyword list used for
   classification. Read the Javadoc on `iniciaNovoPedidoTfd` and the comment block above
   `classificador.tfd-cabecalho-novo-documento` in `application.properties` before touching this — it exists
   because of a real production bug: a genuine TFD/RS continuation page (e.g. a checklist justifying the request)
   naturally mentions phrases like "tratamento fora de domicílio" in body text, which the broad
   `classificador.tfd-outros-estados` list matches, wrongly signaling "new document starts here" and truncating the
   block. The fix uses a two-tier signal: Gemini's explicit `INICIO`/`CONTINUACAO` verdict when available (trusted
   over keywords), else a match against the *narrow*, header-only `tfd-cabecalho-novo-documento` list. Never widen
   that list with anything that could plausibly appear in running prose — it exists specifically to be stricter
   than `tfd-outros-estados`.

   `TFD_RS` vs `TFD_OUTROS_ESTADOS` is itself a hard business requirement, not a nice-to-have: TFD/RS means
   *specifically* Rio Grande do Sul's own form (identified by its literal header text — "CENTRAL ESTADUAL DE
   TRANSPLANTES", "DEPARTAMENTO DE REGULAÇÃO ESTADUAL", "Solicitação de cadastro para consulta -TFD" — see
   `classificador.tfd-rs`). TFD requests from any other state must land in `TFD_OUTROS_ESTADOS` instead, never be
   merged into `TFD_RS`.

3. **Grouping + zip assembly** (`agruparPorCategoria`, `montarZip`): pages are grouped by final category (in
   `Categoria` enum order) and each group becomes one merged PDF named `<categoria>.pdf` inside a flat zip (no
   subfolders — this was an explicit user requirement, not an oversight).

4. **Classification report**: `gerarRelatorioJson` builds a per-page JSON report (category + method per page, plus
   totals per method) and it's returned to the client as base64 in the `X-Relatorio-Classificacao` response header
   — deliberately NOT embedded as a file inside the zip (also an explicit requirement: the zip should contain only
   the category PDFs). The frontend (`index.html`) decodes that header to render the "how was each page classified"
   summary after a successful upload.

## Adding a new category

Add the enum value in `Categoria`, its keyword list + Gemini prompt entry, and decide whether it should participate
in `CATEGORIAS_TFD`/the block-extension rule (only TFD-like multi-page bundles need that; most categories don't).

## Frontend

`index.html` is a single static file with no build step, no framework, and no bundler — inline `<style>`/`<script>`,
vanilla JS, `XMLHttpRequest` (not `fetch`) specifically to get upload progress events. It loads JSZip from a CDN
only to list the zip's contents client-side for the result summary (the actual download is a raw blob, not
JSZip-produced). Edits take effect immediately in `quarkus:dev` — no separate frontend build/watch process.

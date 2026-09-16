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

`PdfSplitResource.split()` → `PdfSplitService.separar()` extracts every page's text with PDFBox
(`extrairTextos`), then branches on `classificador.modo` (config: `CONTEXTO` default, `PAGINA` legacy/rollback — see
below), groups the resulting per-page `Categoria` into a merged PDF per category, and builds the classification
report. Both modes converge on the same `List<PaginaClassificada>` shape, `agruparPorCategoria`/`montarZip`, and
`gerarRelatorioJson`.

### Modo CONTEXTO (default) — `AgrupadorContextualService`

This is the reason the pipeline was redesigned: classifying one page at a time, with zero visibility into
neighboring pages, kept mis-grouping multi-page bundles (see "History" below for the specific bug that triggered
the rewrite). Instead, `AgrupadorContextualService.classificar` sends the Gemini API a **window of several pages at
once** (`classificador.contexto-paginas-por-janela`, default 10) plus a few pages of read-only context immediately
before the window (`classificador.contexto-paginas-de-contexto`, default 4, truncated per-page at
`classificador.contexto-max-caracteres-por-pagina`), and asks it to directly **segment the window into documents** —
contiguous page ranges belonging to the same physical request — with a category each
(`GeminiClassificadorService.agrupar` / `montarPromptAgrupamento`). The response is one line per page,
`numero_da_pagina|numero_do_documento|CATEGORIA`, parsed defensively by `InterpretadorAgrupamento` (a line that
doesn't match the pattern is skipped, not fatal — a lost line becomes one unresolved page, not a discarded
response).

Key pieces:
- **Windows run sequentially** (not parallel) for now — simpler to reason about correctness-wise; if latency on
  large PDFs (50+ pages) becomes a problem, parallelizing across windows (each window's Gemini call is independent
  given its own context slice) is the natural next step, capped at a few concurrent calls to avoid 429s.
- **Cross-window stitching** is a plain Java rule in `AgrupadorContextualService.classificar`, not another prompt
  round-trip: if the first document of window *k* starts exactly where the last document of window *k-1* ended,
  both have the *same* category, and that category is TFD (`TFD_RS`/`TFD_OUTROS_ESTADOS`), they're merged into one
  bundle. Restricted to TFD on purpose — that's the only category where a request routinely spans a window boundary
  (10 pages); merging arbitrary same-category singles just because they're adjacent would be over-eager elsewhere.
- **`TFD_RS` gets a hard Java guard, never just trusted from the model**: `algumaPaginaConfirmaRs` demotes a
  Gemini-labeled `TFD_RS` document to `TFD_OUTROS_ESTADOS` unless at least one of its pages actually contains both a
  `classificador.tfd-rs-marcador` term and a `classificador.tfd-termo-generico` term — the same condition
  `ClassificadorPalavraChaveService.classificar` uses. This is deliberately not symmetric: a stray RS marker never
  promotes a document *to* `TFD_RS`.
- **Fallback is per-window, not per-document**: if a window's Gemini call fails after retry, or comes back with
  under 60% of its pages resolved (`COBERTURA_MINIMA`), the *entire* window falls back to
  `ClassificadorPalavraChaveService.classificar` page-by-page (no TFD block-stitching within that fallback stretch —
  a deliberate simplification; `PAGINA` mode remains the answer if that ever proves insufficient on real data). A
  single unresolved page *within* an otherwise-successful window inherits the previous page's category if there is
  one (holes in the middle of an identified block are overwhelmingly likely to be continuations of it), else falls
  back to keyword classification for just that page.
- **No Gemini key at all** still works: `geminiService.disponivel()` is checked per window, so every window
  immediately takes the fallback path above — equivalent to running keyword-only classification per-window (without
  TFD stitching across window boundaries in that condition, which only matters for a bundle that happens to straddle
  a 10-page boundary).

### Modo PAGINA (legacy, rollback switch) — page-by-page + block-extension heuristics

Set `classificador.modo=PAGINA` (e.g. as a Render env var, no redeploy needed) to fall back to the original
approach if `CONTEXTO` ever misbehaves on a real document in a way `PAGINA` didn't: `classificarPaginasPorTexto`
classifies each page **in isolation** and in parallel (`classificarTexto` → Gemini first via
`GeminiClassificadorService.classificar`, falling back to `ClassificadorPalavraChaveService.classificar` if Gemini
is unavailable/fails/answers `OUTROS`), then `estenderBlocosTfd` tries to reconstruct multi-page TFD bundles
after the fact using per-page heuristics (`deveInterromperBloco`, `iniciaNovoPedidoTfd`, `puxarCapaParaTras`,
config `classificador.tfd-rs-paginas-por-bloco` / `tfd-cabecalho-novo-documento` /
`tfd-exigir-cabecalho-para-quebrar-bloco`). These heuristics are documented in detail in code comments in
`PdfSplitService` and are the accumulated fix history for a chain of real bugs (RS/other-state bundles getting
mixed, continuation pages losing their cover, a same-state laudo médico attachment getting mistaken for a new
document's cover — see git log around commits `76a5e86`/`5e4d416`). **This whole apparatus exists only because
per-page classification has no visibility into neighboring pages** — that's exactly the limitation `CONTEXTO` mode
was built to remove. Don't extend this heuristic further; if a new PAGINA-mode edge case shows up, prefer improving
the `CONTEXTO` prompt/stitching logic instead, since PAGINA is meant to stay a frozen rollback path.

### Grouping, zip assembly, and the report (both modes)

- **Grouping + zip assembly** (`agruparPorCategoria`, `montarZip`): pages are grouped by final category (in
  `Categoria` enum order) and each group becomes one merged PDF named `<categoria>.pdf` inside a flat zip (no
  subfolders — explicit user requirement).
- **Classification report** (`gerarRelatorioJson`): per-page category + method, totals per method, and — since the
  CONTEXTO redesign — a `documentos` array of contiguous same-category page ranges (`gerarBlocosDeDocumento`,
  e.g. `{"inicio":1,"fim":5,"categoria":"tfd_rs"}`), which is the most direct way for the user to sanity-check
  whether the grouping matches what they see reading the PDF. Delivered as base64 in the `X-Relatorio-Classificacao`
  response header, never as a file inside the zip (explicit requirement). Per-page detail capped at 500 entries
  (`paginasTruncadas: true` when cut) so a huge PDF can't blow past a proxy's header-size limit; per-method totals
  always cover every page regardless of the cap.

## Other things worth knowing

- **`quarkus.rest-client.gemini-api.read-timeout` is 45000ms**, not the 15000ms it used to be — a CONTEXTO-mode
  window call sends ~10+ pages of text and genuinely takes longer than a single-page call did. If this ever gets
  changed back down, every CONTEXTO call will silently miss the timeout and the app will look "broken" (always
  falling back to keywords) with no obvious error — check this first if Gemini classification seems to have
  stopped working after a config change.
- **Pages with no extractable text** (scanned image with no OCR layer) skip the Gemini call entirely — there's
  nothing to send — and are tagged `MetodoClassificacao.SEM_TEXTO` in the report instead of silently landing in
  `outros.pdf` with no indication anything went wrong.
- **Invalid input PDFs** (corrupt, password-protected, zero pages) throw `PdfInvalidoException` from
  `PdfSplitService.separar`, which `PdfSplitResource` turns into an HTTP 400 with a Portuguese message instead of a
  raw 500 stack trace.
- **The uploaded PDF is read directly from Quarkus's temp upload file** (`formulario.file.uploadedFile().toFile()`
  passed straight to `Loader.loadPDF(File)`), not buffered into a byte array first — matters on Render's free tier,
  which is memory-constrained.
- **The source PDF's AcroForm is flattened** (`achatarFormulario`) before splitting, so a digitally-filled form (e.g.
  a TFD form filled on a computer, not printed/scanned) doesn't come out with blank-looking fields in the split PDF
  — `importPage` copies page content but not form field values on its own.

## Adding a new category

Add the enum value in `Categoria`, its keyword list (`ClassificadorPalavraChaveService`/`application.properties`)
and its entry in both Gemini prompts (`GeminiClassificadorService.montarPrompt` for PAGINA mode,
`montarPromptAgrupamento` for CONTEXTO mode — keep the category descriptions consistent between the two, they drift
easily since they're separate string blocks), and decide whether it should participate in the TFD-only
cross-window stitching (`CATEGORIAS_TFD`, currently duplicated as a small `EnumSet` in both `PdfSplitService` and
`AgrupadorContextualService` — only TFD-like multi-page bundles need that; most categories don't).

## Frontend

`index.html` is a single static file with no build step, no framework, and no bundler — inline `<style>`/`<script>`,
vanilla JS, `XMLHttpRequest` (not `fetch`) specifically to get upload progress events. It loads JSZip from a CDN
only to list the zip's contents client-side for the result summary (the actual download is a raw blob, not
JSZip-produced). Edits take effect immediately in `quarkus:dev` — no separate frontend build/watch process.

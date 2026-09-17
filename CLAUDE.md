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

CI/CD is now wired up via `.github/workflows/deploy.yml`: every push to `main` triggers a GitHub Actions job that
POSTs to the Render deploy API for this service. This works around there being no GitHub App installed on the
`centraldetransplante-cyber` org (so Render's own push-triggered auto-deploy doesn't fire) — the Action carries the
trigger instead.

One-time setup (manual, needs GitHub repo admin access): add a repo secret named `RENDER_API_KEY` with a Render API
key at https://github.com/centraldetransplante-cyber/new/settings/secrets/actions. Until that secret exists, the
workflow runs on every push but the deploy step fails with 401 — check the Actions tab if a push doesn't show up on
Render.

Manual trigger (still works, e.g. to redeploy without a code change):

```shell
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
(`GeminiClassificadorService.agrupar` / `montarPromptAgrupamento`). Since 2026-09-16 the call uses Gemini's
`responseSchema`/`responseMimeType=application/json` (`GeminiRequest.deJsonAgrupamento`) to force a JSON array of
`{"pagina": N, "documento": N, "categoria": "..."}` — one item per page — instead of a free-text
`numero|numero|CATEGORIA` line format parsed by regex. `InterpretadorAgrupamento` still parses defensively at the
*item* level (an item with an unrecognized category or a page outside the window is dropped individually, not
fatal — same "lost item becomes one unresolved page" philosophy as before); the difference is that a fully
malformed/truncated response (e.g. hitting `maxOutputTokens` on a very text-heavy window) now fails JSON parsing
entirely and treats the whole window as unresolved, which the caller already handles as a low-coverage window
(falls back to keyword classification for that window).

Key pieces:
- **Windows run sequentially** (not parallel) for now — simpler to reason about correctness-wise; if latency on
  large PDFs (50+ pages) becomes a problem, parallelizing across windows (each window's Gemini call is independent
  given its own context slice) is the natural next step, capped at a few concurrent calls to avoid 429s.
- **Bundle state is tracked per page, not as a single "open document" variable** (`AgrupadorContextualService`,
  `EstadoBundle[] estadoPorPagina`, one slot per page, persisted across the whole `classificar()` call — not reset
  per window). Both the documents loop and the unresolved-pages loop write into it, and both look up
  "the state of the page immediately before this one" from the array rather than from a loop-local variable. This
  is what makes cross-window/cross-gap stitching correct regardless of which loop last touched a given page — see
  the 2026-09-17 bug fix below for why that distinction mattered. `EstadoBundle` keeps the RAW category Gemini gave
  a document *and* the EFFECTIVE (possibly downgraded) one separately; continuation checks always compare raw-to-raw,
  never raw-to-effective.
- **Cross-window/cross-gap stitching**: if a new document starts exactly where the tracked state of the previous
  page ended, both have the *same RAW* category, and that category is TFD (`TFD_RS`/`TFD_OUTROS_ESTADOS`), it's
  treated as a continuation of the same bundle (inherits RS-confirmation if applicable). Restricted to TFD on
  purpose — that's the only category where a request routinely spans a window boundary (10 pages); merging
  arbitrary same-category singles just because they're adjacent would be over-eager elsewhere.
- **Pages with no real content ("pobre") get absorbed into an open TFD bundle regardless of what raw category
  Gemini gave them** (`ClassificadorPalavraChaveService.paginaPobre` + the `absorverNoBundle` check in
  `AgrupadorContextualService`). A "pobre" page is one where, after stripping known protocol-stamp/boilerplate
  phrases (`classificador.contexto-carimbo-protocolo-padroes`), fewer than `classificador.contexto-min-caracteres-
  conteudo-util` letters remain — common for scanned attachments with no OCR text layer, where PDFBox only picks up
  an e-protocolo validation stamp. Guards against over-absorption: a page that self-confirms `TFD_RS` on its own
  merits (own RS marker) is never absorbed instead of trusted directly; a page matching
  `classificador.tfd-cabecalho-novo-documento` (capa of a genuinely new request) is never absorbed either.
- **`TFD_RS` gets a hard Java guard, never just trusted from the model**: `algumaPaginaConfirmaRs` checks that at
  least one of a document's pages actually contains both a `classificador.tfd-rs-marcador` term and a
  `classificador.tfd-termo-generico` term before accepting `TFD_RS`. If it fails and the document isn't rescued by
  bundle continuation/absorption above, it downgrades to `TFD_OUTROS_ESTADOS` — *unless* the document is also
  "pobre" (no real content at all), in which case it becomes `OUTROS` instead: fabricating an affirmative business
  category (`TFD_OUTROS_ESTADOS`, "this is a TFD request from another state") from zero actual content was itself a
  bug (see below), not a safe default. This is deliberately not symmetric: a stray RS marker never promotes a
  document *to* `TFD_RS`.
- **Fallback is per-window, not per-document**: if a window's Gemini call fails after retry, or comes back with
  under 60% of its pages resolved (`COBERTURA_MINIMA`), the *entire* window falls back to
  `ClassificadorPalavraChaveService.classificar` page-by-page (no TFD block-stitching within that fallback stretch —
  a deliberate simplification; `PAGINA` mode remains the answer if that ever proves insufficient on real data). A
  single unresolved page *within* an otherwise-successful window inherits the tracked state of the previous page if
  there is one (holes in the middle of an identified block are overwhelmingly likely to be continuations of it),
  else falls back to keyword classification for just that page.

**Bug fixed 2026-09-17** (real production PDF, protocol `26.255.858-4`): a TFD/RS request with several scanned
attachment pages with no OCR text (only an e-protocolo validation stamp survived extraction) came out with those
pages in `tfd_outros_estados.pdf` instead of staying in the `tfd_rs` bundle. Root cause was four compounding
bugs, all in the pre-2026-09-17 version of `AgrupadorContextualService`: (1) continuation compared a new document's
raw category to the *previous document's already-downgraded* category instead of its raw one; (2) the "open
document" state was a loop-local variable only updated inside the documents loop, so an unresolved page at the end
of a window silently broke stitching with the next window; (3) the RS guard downgraded a document with *zero real
content* to the affirmative `TFD_OUTROS_ESTADOS` instead of a neutral category; (4) there was no concept anywhere
in the pipeline of "page with no real content" distinct from `isBlank()` — a page that's 90% e-protocolo stamp
boilerplate looks like "has content" to every existing check. Root-caused and the fix designed by an Opus agent
(report only, no code — see [[feedback-opus-design-then-sonnet-implement]]), implemented and verified end-to-end
against the live Render deployment with a synthetic reproduction PDF (12 pages: real RS capa + 10 stamp-only pages
straddling a window boundary) before being trusted. If a future report says "TFD_RS/TFD_OUTROS_ESTADOS came out
wrong" again, re-check this exact area first — see [[feedback-tfd-domain-rules]].

- **Follow-up hardening (same day, found by `/code-review ultra` run repeatedly against the fix above)**: a short
  page (few real letters) that raw-matches `classificador.identificacao-pessoal-inequivoca` (a deliberately narrow
  RG/CPF/SUS-card list, NOT the broader `classificador.documentos` — that one includes ambiguous terms like
  "encaminhamento" that can legitimately appear in real TFD prose) or `classificador.protocolo-encaminhamento` is
  routed to that category (`AgrupadorContextualService.categoriaAutoEvidenteOuNull`) instead of being
  absorbed/downgraded as if it had zero content — an RG copy attached to a TFD request is short but not "nothing".
  Own-page `TFD_RS` self-confirmation (a real RS marker) always wins over this keyword reroute, and the reroute
  only fires when the page is also "pobre" — rich TFD content that happens to mention an incidental word (e.g.
  "encaminhamento" in running clinical text) is never pulled out of its bundle by this. Deliberately does **not**
  consider `classificador.exames` (a genuine LAUDO MÉDICO attachment reads as "exame"-ish per business rule #2 in
  [[feedback-tfd-domain-rules]] and must stay absorbable into the RS bundle, not get rerouted to `exames.pdf`).
  **Known remaining gap, not yet fixed**: if this keyword reroute fires on a page in the middle of an otherwise
  contiguous TFD run (e.g. an RG sandwiched between TFD pages), the "open bundle" tracking
  (`estadoAnterior.categoriaEfetiva()`) closes at that page, so a *further* trailing poor/unconfirmed TFD-labeled
  page right after the RG won't reconnect to the original TFD bundle it should still belong to. Would need the
  bundle state to track "last TFD-thread state" separately from "last page's state" to fix properly — not done
  because the triggering sequence (confirmed TFD → RG interruption → another unconfirmed poor TFD page) is a
  fairly narrow compound case and the current fallback (that trailing page becomes `OUTROS`, not silently wrong)
  isn't unsafe, just suboptimal. Revisit if a real report shows this pattern.
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
- **Per-category size cap** (`comprimirSePreciso`, `classificador.tamanho-maximo-arquivo-mb`, default 10): if a
  category's merged PDF comes out bigger than the limit (common for `exames.pdf` with many scanned images), its
  images are recompressed as JPEG at progressively lower quality (`QUALIDADES_COMPRESSAO`: 0.6 → 0.08) via
  `recomprimirImagensDosRecursos`/`JPEGFactory.createFromImage`, stopping at the first quality level that fits.
  Never drops the file even if the strongest compression still doesn't fit under the limit — just logs a warning
  and ships the smallest version achieved. This is deliberately a *per-output-file* limit, not an upload-size limit
  (that's the separate `quarkus.http.limits.max-body-size`) — the user asked specifically for the split PDFs
  themselves to stay under a size cap, not the original combined upload.
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
vanilla JS, `XMLHttpRequest` (not `fetch`) specifically to get upload progress events. JSZip is vendored locally at
`vendor/jszip.min.js` (no external CDN dependency since 2026-09-16) only to list the zip's contents client-side for
the result summary (the actual download is a raw blob, not JSZip-produced). A yellow banner (`#metodoAlerta`)
warns the user when any page fell back to keyword classification (Gemini unavailable/failed), on top of the
existing per-page method badges. Edits take effect immediately in `quarkus:dev` — no separate frontend build/watch
process.

## Tests

`src/test/java` has unit tests for the three services identified as untested in the Antigravity audit:
`ClassificadorPalavraChaveServiceTest`, `InterpretadorAgrupamentoTest`, and `AgrupadorContextualServiceTest` (the
last one uses a hand-written `GeminiClassificadorService` subclass as a test double — no mocking framework is a
project dependency). Run with `./mvnw test`.

## Notes & Collaboration (Antigravity AI 🤝 Claude Code)

- **Audit Review & Fixes**: Antigravity AI performed a code audit (`RELATORIO_BUGS_E_MELHORIAS.md`) and Claude Code
  successfully reviewed and applied key fixes to production (page-level extraction exception handling, header size
  limit reduction to 150 pages, Base64 UTF-8 decoding via `TextDecoder` in `index.html`, PDF compression
  optimization, and GitHub Actions CI deploy workflow).
- **Two audit findings were false positives, verified 2026-09-16**: (1) "invalid Gemini model
  `gemini-3.5-flash-lite`" — that model exists (Gemini 3 series, released after the audit's apparent knowledge
  cutoff); config is correct, left unchanged. (2) "greedy `0*` in the agrupamento regex breaks docId `0`" — tested
  directly against the JDK regex engine and Java's backtracking handles it correctly (`0*` gives back the digit
  when the mandatory `\d{1,5}` group needs it). Verify audit findings against the actual running code/behavior
  before "fixing" them — an AI-generated audit can be wrong, especially about what does/doesn't exist in a fast-
  moving API surface.
- **Remaining items from the audit, addressed 2026-09-16**: JSZip vendored locally (was CDN-only), a UI banner for
  Gemini-fallback pages (was silent, badges only), unit tests added for the three services above, and the
  agrupamento call migrated to structured JSON output (`responseSchema`) — see "Modo CONTEXTO" above. **Left
  unchanged on purpose**: the fixed-size `Executors.newFixedThreadPool(8)` in `PdfSplitService` (the audit
  suggested virtual threads, but the pool's fixed size of 8 is a deliberate rate-limit guard against 429s from the
  Gemini API in PAGINA mode, not just a thread-overhead optimization — switching to virtual threads would remove
  that cap); and the compression loop in `comprimirSePreciso` reusing the same `PDDocument` across quality
  attempts (a documented, deliberate trade-off against the CPU/memory cost of reloading a large PDF from bytes up
  to 5 times, not an oversight).


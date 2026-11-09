# libwebp (vendored subset)

- Upstream: https://github.com/webmproject/libwebp (Google, BSD-3-Clause, see `COPYING` and `PATENTS`)
- Version: tag **v1.5.0**
- Source archive: `https://github.com/webmproject/libwebp/archive/refs/tags/v1.5.0.tar.gz`
  - SHA-256: `668c9aba45565e24c27e17f7aaf7060a399f7f31dba6c97a044e1feacb930f37`
- Files are copied **unmodified**.

## Included (decoding only)
- `src/dec/` – VP8/VP8L/alpha decoder
- `src/demux/demux.c`, `src/demux/anim_decode.c` – container demuxer and animation compositor (`WebPAnimDecoder`)
- `src/utils/` – the decoder's "common" sources from upstream `src/utils/Makefile.am`
- `src/dsp/` – the decoder's common, SSE2, SSE4.1 and NEON sources from upstream `src/dsp/Makefile.am` (no MIPS/MSA)
- `src/webp/` – public headers
- `src/enc/histogram_enc.h`, `src/enc/backward_references_enc.h` – headers only, included by `src/dsp/lossless.h` (no encoder sources)
- `COPYING`, `PATENTS`, `AUTHORS`

Not included: encoder (`src/enc`), mux (`src/mux`), `sharpyuv`, examples, tests, build files.

## Updating
Download a new release tag, verify its checksum, and copy the same file set again. Then update the version and checksum above and in `THIRD_PARTY_NOTICES.md` at the repository root.

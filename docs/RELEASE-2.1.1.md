SentinelAE 2.1.1: chunk layout from the server version, verified rewrites, chunk diagnostics.

- **The section layout (fluid count, length prefix) is taken from the running Minecraft version** instead of the first layout that happens to read a chunk exactly. An all-air chunk fits several layouts (24 empty 26.x sections are also a complete read in another layout), so the old guess could be wrong. The server's layout is tried first; any other layout is used only when it is the only one that fits; an ambiguous chunk is left unchanged.
- **The rewrite is verified.** It runs on a copy and replaces the packet's bytes only when the section structure is unchanged.
- **Chunks that cannot be read exactly are reported:** counted in the status line (`chunks unreadable/ambiguous/rejected`) and logged with the chunk coordinates, size and first bytes (at most one line every 30 s). Anything that reads chunk packets after AE (an anticheat) may fail on exactly such a chunk.

Tests: 26 unit tests (new: version-to-layout mapping, the all-air ambiguity, the server layout winning, unreadable chunks untouched); AE probe 19/19 on Paper 26.2, 26.3, 1.21.11 and 1.21.1 with the other four plugins installed.

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Checksums are in `SHA256SUMS.txt`.

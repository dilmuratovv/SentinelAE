SentinelAE 2.0.0: anti-ESP for Paper 1.21 through 26.3 (one JAR, Java 21; Java 25 on 26.x). Works with any client version through ViaVersion/ViaBackwards.

- Players and armor stands are only sent to a viewer while the viewer could actually see them: an exact voxel raycast to 15 points of the target, with lag compensation (look-ahead and box margin) so players running around a corner appear in time.
- Concealed targets are removed from Paper's tracker for that viewer: **no packet at all** (spawn, movement, equipment, metadata) about them reaches the client, and they stay in the tab list. If Paper internals ever change, AE falls back to the hide/show API automatically.
- Budgeted scheduling (`pairs-per-tick`, `max-micros-per-tick`); 42 players in a dense crowd cost about 1.3 ms per tick in testing.
- `/sae status`, `/sae check <viewer> <target>`, `/sae reload`; `sentinelae.bypass`.

Tested on Paper 1.21, 1.21.4, 1.21.11 and 26.3 (27/27 real-client checks each). Not covered: sounds, particles and other entity types; Folia.

The attached JAR is signed with the Sentinel suite certificate (`CN=Sentinel Suite`, SHA-256 `64:10:FE:2E:43:F2:23:F6:BD:55:94:BA:20:26:F2:54:0B:36:D7:26:F1:2D:69:4E:4C:B5:BF:22:03:C3:82:91`, self-signed). Check with `jarsigner -verify -certs <jar>`. Checksums are in `SHA256SUMS.txt`. License: GPL-3.0.


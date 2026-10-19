# Private backup status

- Google Drive direct upload still BLOCKED with HTTP 403; native Drive copy reported **storageQuotaExceeded** (owner account storage quota).
- Existing Drive folder and prior v0.33 backup unchanged.
- Private ChatGPT Library temporary folder: `CodeFit Knowledge Compiler Recovery`. Both **v0.34 and v0.35 private checkpoint ZIPs** uploaded there and independently materialized/read back; observed SHA256 matched each original local ZIP.
- v0.34 outer hash: `905feee30d5d56f1f355511b3120f11c06794dd9d8d4f3e0b0697bf2e5d24e9c`.
- v0.35 outer hash: `cf567ab3e55f16c9db1ae2ae5d0de4c137e42e18951f4628c483b17f088c2672`.
- After owner frees Drive storage or increases quota, upload exact private archives to the original project folder without overwriting v0.33; download and verify outer SHA-256 and manifest.
- Do not delete older historical checkpoints without an explicit owner retention decision.

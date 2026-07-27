# Approved Junior Training Sheet importer fixture

This repository contains one explicitly approved workbook fixture at:

`data/import-fixtures/Ahmed-Junior-Training-Sheet-V7.0.xlsx`

The fixture exists so the real CodeFit importer can be validated against the workbook structure that the product must support. It is not loaded automatically, seeded into the application database, or packaged as a built-in curriculum.

## Local import

1. Build and launch CodeFit.
2. Open **Settings → Problem-Solving Training**.
3. Select **Import Training Sheet…**.
4. Choose `data/import-fixtures/Ahmed-Junior-Training-Sheet-V7.0.xlsx`.
5. Review the import summary before continuing with the Problems area.

## Source

Public source document:

`https://docs.google.com/spreadsheets/d/1jZjaGtG-N_-dZfBPQJ3sRS0mmC-F0J4c/edit?usp=drivesdk&ouid=110394889893843993289&rtpof=true&sd=true`

The repository policy test allows only this exact `.xlsx` path. Any additional or replacement workbook requires an explicit review of source ownership, purpose, packaging, and test impact.

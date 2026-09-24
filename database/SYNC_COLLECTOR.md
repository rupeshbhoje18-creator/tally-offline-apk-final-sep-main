# Tally Offline Company Page Mirror

The app now mirrors **whole authenticated portal pages**, instead of requiring a Ledger/Voucher/Stock HTML table.

## Behaviour

1. After a portal page finishes loading, the app automatically captures that rendered page.
2. The captured page is grouped under the detected Tally company in Room.
3. **SAVE THIS PAGE** stores the current page again on demand.
4. **SYNC WHOLE COMPANY** starts a safe same-origin crawl. It discovers normal GET navigation links inside `/customerapp/`, opens them one by one, captures the rendered pages, and keeps the progress in `localStorage` so page-to-page navigation does not lose the queue.
5. The crawler skips links that look like logout, delete, save, update, submit, upload, import/export, or other state-changing actions. It is limited to 250 pages per crawl.
6. Page HTML is sanitized (scripts/forms/frames removed) and GZIP-compressed before it is saved in `page_snapshots`.
7. Offline Data shows each saved company and its page count. Opening a company shows the saved pages, which can be viewed without network access.

## Accounting data

The previous structured Ledger/Voucher/Stock collector remains available for structured report data, but it is no longer required for page mirroring. The page mirror never invents accounting values.

## Important limitation

A browser cannot know about every server-side page that is not linked or rendered in the current authenticated session. The company crawl therefore captures every safe page it can discover through the portal's same-origin navigation graph, while automatic capture ensures pages opened manually are also stored.

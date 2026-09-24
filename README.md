# Tally Offline Customer App

This Android project wraps the authenticated Tally Customer Portal in a WebView and maintains a local Room database.

## Offline company mirror

The current build stores the **whole rendered portal page**, not only Ledger/Voucher/Stock tables.

- `SYNC WHOLE COMPANY`: discovers safe same-origin GET navigation links and mirrors up to 250 pages per crawl.
- `SAVE THIS PAGE`: stores the current page immediately.
- Every portal page is also auto-captured after page load.
- Offline Data groups saved pages under the Tally company and lets the user open an offline copy.

Structured Ledger/Voucher/Stock capture is retained as an additional feature, but page mirroring does not depend on those report tables.

PRAGMA foreign_keys = ON;

CREATE TABLE companies (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  companyName TEXT NOT NULL,
  serialNumber TEXT NOT NULL UNIQUE,
  gstin TEXT,
  financialYearFrom TEXT,
  lastSynced INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE ledgers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  companyId INTEGER NOT NULL,
  guid TEXT NOT NULL,
  name TEXT NOT NULL,
  parent TEXT,
  openingBalance REAL NOT NULL DEFAULT 0,
  closingBalance REAL NOT NULL DEFAULT 0,
  alteredOn INTEGER,
  FOREIGN KEY(companyId) REFERENCES companies(id) ON DELETE CASCADE,
  UNIQUE(companyId, guid)
);

CREATE TABLE vouchers (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  companyId INTEGER NOT NULL,
  guid TEXT NOT NULL,
  date TEXT NOT NULL,
  voucherType TEXT NOT NULL,
  voucherNumber TEXT,
  partyName TEXT,
  amount REAL NOT NULL DEFAULT 0,
  narration TEXT,
  alteredOn INTEGER,
  FOREIGN KEY(companyId) REFERENCES companies(id) ON DELETE CASCADE,
  UNIQUE(companyId, guid)
);

CREATE TABLE voucher_entries (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  voucherId INTEGER NOT NULL,
  ledgerGuid TEXT,
  ledgerName TEXT NOT NULL,
  amount REAL NOT NULL DEFAULT 0,
  isDeemedPositive INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(voucherId) REFERENCES vouchers(id) ON DELETE CASCADE
);

CREATE TABLE stock_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  companyId INTEGER NOT NULL,
  guid TEXT NOT NULL,
  name TEXT NOT NULL,
  parent TEXT,
  unit TEXT,
  openingQty REAL NOT NULL DEFAULT 0,
  closingQty REAL NOT NULL DEFAULT 0,
  openingValue REAL NOT NULL DEFAULT 0,
  closingValue REAL NOT NULL DEFAULT 0,
  closingRate REAL NOT NULL DEFAULT 0,
  alteredOn INTEGER,
  FOREIGN KEY(companyId) REFERENCES companies(id) ON DELETE CASCADE,
  UNIQUE(companyId, guid)
);

CREATE TABLE sync_state (
  companyId INTEGER PRIMARY KEY,
  lastAttempt INTEGER NOT NULL DEFAULT 0,
  lastSuccess INTEGER,
  status TEXT NOT NULL DEFAULT 'NEVER_SYNCED',
  message TEXT
);

CREATE TABLE page_snapshots (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  companyId INTEGER NOT NULL,
  pageKey TEXT NOT NULL,
  title TEXT NOT NULL,
  url TEXT NOT NULL,
  route TEXT NOT NULL,
  htmlGzip BLOB NOT NULL,
  contentHash TEXT NOT NULL,
  capturedAt INTEGER NOT NULL,
  FOREIGN KEY(companyId) REFERENCES companies(id) ON DELETE CASCADE,
  UNIQUE(companyId, pageKey)
);

CREATE INDEX idx_ledgers_company ON ledgers(companyId);
CREATE INDEX idx_ledgers_company_name ON ledgers(companyId, name);
CREATE INDEX idx_vouchers_company ON vouchers(companyId);
CREATE INDEX idx_vouchers_company_date ON vouchers(companyId, date);
CREATE INDEX idx_voucher_entries_voucher ON voucher_entries(voucherId);
CREATE INDEX idx_stock_items_company ON stock_items(companyId);
CREATE INDEX idx_stock_items_company_name ON stock_items(companyId, name);
CREATE INDEX idx_page_snapshots_company ON page_snapshots(companyId);
CREATE INDEX idx_page_snapshots_company_captured ON page_snapshots(companyId, capturedAt);

package com.example.tallycustomerapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class OfflineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertCompanyEntity(entity: CompanyEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertLedgers(entities: List<LedgerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertVouchers(entities: List<VoucherEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertVoucherEntries(entities: List<VoucherEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertStockItems(entities: List<StockItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSyncState(entity: SyncStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertPageSnapshot(entity: PageSnapshotEntity)

    @Query("SELECT id FROM companies WHERE serialNumber = :serialNumber LIMIT 1")
    protected abstract suspend fun findCompanyId(serialNumber: String): Long?

    @Query("SELECT id FROM companies WHERE companyName = :companyName ORDER BY lastSynced DESC LIMIT 1")
    protected abstract suspend fun findCompanyIdByName(companyName: String): Long?

    @Query("DELETE FROM ledgers WHERE companyId = :companyId")
    protected abstract suspend fun deleteAllLedgers(companyId: Long)

    @Query("DELETE FROM vouchers WHERE companyId = :companyId")
    protected abstract suspend fun deleteAllVouchers(companyId: Long)

    @Query("DELETE FROM stock_items WHERE companyId = :companyId")
    protected abstract suspend fun deleteAllStockItems(companyId: Long)

    @Query("DELETE FROM ledgers WHERE companyId = :companyId AND guid IN (:guids)")
    protected abstract suspend fun deleteLedgersByGuid(companyId: Long, guids: List<String>)

    @Query("DELETE FROM vouchers WHERE companyId = :companyId AND guid IN (:guids)")
    protected abstract suspend fun deleteVouchersByGuid(companyId: Long, guids: List<String>)

    @Query("DELETE FROM stock_items WHERE companyId = :companyId AND guid IN (:guids)")
    protected abstract suspend fun deleteStockItemsByGuid(companyId: Long, guids: List<String>)

    @Query("SELECT * FROM companies ORDER BY lastSynced DESC")
    abstract suspend fun getAllCompanies(): List<CompanyEntity>

    @Query("SELECT * FROM ledgers WHERE companyId = :companyId ORDER BY name")
    abstract suspend fun getLedgers(companyId: Long): List<LedgerEntity>

    @Query("SELECT * FROM vouchers WHERE companyId = :companyId ORDER BY date DESC, id DESC")
    abstract suspend fun getVouchers(companyId: Long): List<VoucherEntity>

    @Query("SELECT * FROM voucher_entries WHERE voucherId = :voucherId ORDER BY id")
    abstract suspend fun getVoucherEntries(voucherId: Long): List<VoucherEntryEntity>

    @Query("SELECT * FROM stock_items WHERE companyId = :companyId ORDER BY name")
    abstract suspend fun getStockItems(companyId: Long): List<StockItemEntity>

    @Query("SELECT * FROM page_snapshots WHERE companyId = :companyId ORDER BY capturedAt DESC")
    abstract suspend fun getPageSnapshots(companyId: Long): List<PageSnapshotEntity>

    @Query("SELECT * FROM page_snapshots WHERE id = :id LIMIT 1")
    abstract suspend fun getPageSnapshot(id: Long): PageSnapshotEntity?

    @Query("SELECT COUNT(*) FROM page_snapshots WHERE companyId = :companyId")
    abstract suspend fun getPageCount(companyId: Long): Int

    @Query("SELECT COUNT(*) FROM ledgers WHERE companyId = :companyId")
    abstract suspend fun getLedgerCount(companyId: Long): Int

    @Query("SELECT COUNT(*) FROM vouchers WHERE companyId = :companyId")
    abstract suspend fun getVoucherCount(companyId: Long): Int

    @Query("SELECT COUNT(*) FROM stock_items WHERE companyId = :companyId")
    abstract suspend fun getStockCount(companyId: Long): Int

    /**
     * Saves structured Tally data without destroying unrelated reports unless a true full snapshot is requested.
     */
    @Transaction
    open suspend fun saveCollectedData(
        company: CompanyEntity,
        ledgers: List<LedgerEntity>,
        vouchers: List<VoucherEntity>,
        voucherEntriesByGuid: Map<String, List<VoucherEntryInput>>,
        stockItems: List<StockItemEntity>,
        replaceAll: Boolean,
        collectedSections: Set<String>
    ): Long {
        val companyId = upsertCompany(company)

        if (replaceAll || "ledgers" in collectedSections) {
            if (replaceAll) deleteAllLedgers(companyId)
            val prepared = ledgers.map { it.copy(companyId = companyId, id = 0) }
            if (!replaceAll && prepared.isNotEmpty()) deleteLedgersByGuid(companyId, prepared.map { it.guid })
            if (prepared.isNotEmpty()) insertLedgers(prepared)
        }

        if (replaceAll || "vouchers" in collectedSections) {
            if (replaceAll) deleteAllVouchers(companyId)
            val prepared = vouchers.map { it.copy(companyId = companyId, id = 0) }
            if (!replaceAll && prepared.isNotEmpty()) deleteVouchersByGuid(companyId, prepared.map { it.guid })
            val insertedIds = if (prepared.isEmpty()) emptyList() else insertVouchers(prepared)
            val guidToId = vouchers.mapIndexed { index, voucher ->
                val guid = voucher.guid.ifBlank {
                    "voucher:${voucher.date}:${voucher.voucherNumber.orEmpty()}:${voucher.voucherType}:${voucher.partyName.orEmpty()}"
                }
                guid to insertedIds[index]
            }.toMap()

            if (replaceAll) {
                // voucher FK cascade removes old entries when vouchers are deleted.
            }
            val entryEntities = voucherEntriesByGuid.flatMap { (voucherGuid, entries) ->
                val voucherId = guidToId[voucherGuid] ?: return@flatMap emptyList()
                entries.map { entry ->
                    VoucherEntryEntity(
                        voucherId = voucherId,
                        ledgerGuid = entry.ledgerGuid,
                        ledgerName = entry.ledgerName,
                        amount = entry.amount,
                        isDeemedPositive = entry.isDeemedPositive
                    )
                }
            }
            if (entryEntities.isNotEmpty()) insertVoucherEntries(entryEntities)
        }

        if (replaceAll || "stockItems" in collectedSections) {
            if (replaceAll) deleteAllStockItems(companyId)
            val prepared = stockItems.map { it.copy(companyId = companyId, id = 0) }
            if (!replaceAll && prepared.isNotEmpty()) deleteStockItemsByGuid(companyId, prepared.map { it.guid })
            if (prepared.isNotEmpty()) insertStockItems(prepared)
        }

        val now = System.currentTimeMillis()
        insertSyncState(
            SyncStateEntity(
                companyId = companyId,
                lastAttempt = now,
                lastSuccess = now,
                status = "SYNCED",
                message = if (replaceAll) "Structured Tally snapshot saved" else "Structured Tally report saved"
            )
        )
        return companyId
    }

    @Transaction
    open suspend fun savePage(
        company: CompanyEntity,
        page: PageSnapshotInput
    ): Long {
        val companyId = upsertCompany(company)
        insertPageSnapshot(
            PageSnapshotEntity(
                id = 0,
                companyId = companyId,
                pageKey = page.pageKey,
                title = page.title,
                url = page.url,
                route = page.route,
                htmlGzip = page.htmlGzip,
                contentHash = page.contentHash,
                capturedAt = page.capturedAt
            )
        )
        insertSyncState(
            SyncStateEntity(
                companyId = companyId,
                lastAttempt = page.capturedAt,
                lastSuccess = page.capturedAt,
                status = "PAGE_SAVED",
                message = "Page mirror saved: ${page.title.ifBlank { page.route }}"
            )
        )
        return companyId
    }

    @Transaction
    protected suspend fun upsertCompany(company: CompanyEntity): Long {
        val existingId = findCompanyId(company.serialNumber)
            ?: findCompanyIdByName(company.companyName)
        val id = existingId ?: 0L
        insertCompanyEntity(company.copy(id = id))
        return if (existingId != null) existingId else findCompanyId(company.serialNumber)
            ?: findCompanyIdByName(company.companyName)
            ?: error("Company could not be created")
    }
}

data class VoucherEntryInput(
    val ledgerGuid: String? = null,
    val ledgerName: String,
    val amount: Double = 0.0,
    val isDeemedPositive: Boolean = false
)

data class PageSnapshotInput(
    val pageKey: String,
    val title: String,
    val url: String,
    val route: String,
    val htmlGzip: ByteArray,
    val contentHash: String,
    val capturedAt: Long
)

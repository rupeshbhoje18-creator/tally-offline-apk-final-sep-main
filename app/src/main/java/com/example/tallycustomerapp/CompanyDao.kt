package com.example.tallycustomerapp.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface CompanyDao {
    @Query(
        "SELECT c.*, " +
            "(SELECT COUNT(*) FROM page_snapshots p WHERE p.companyId = c.id) AS pageCount, " +
            "(SELECT COUNT(*) FROM ledgers l WHERE l.companyId = c.id) AS ledgerCount, " +
            "(SELECT COUNT(*) FROM vouchers v WHERE v.companyId = c.id) AS voucherCount, " +
            "(SELECT COUNT(*) FROM stock_items s WHERE s.companyId = c.id) AS stockCount " +
            "FROM companies c ORDER BY c.lastSynced DESC"
    )
    suspend fun getAllCompanies(): List<CompanySummary>
}

data class CompanySummary(
    val id: Long,
    val companyName: String,
    val serialNumber: String,
    val gstin: String?,
    val financialYearFrom: String?,
    val lastSynced: Long,
    val pageCount: Int,
    val ledgerCount: Int,
    val voucherCount: Int,
    val stockCount: Int
)

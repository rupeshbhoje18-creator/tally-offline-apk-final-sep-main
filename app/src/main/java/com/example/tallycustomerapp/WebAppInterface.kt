package com.example.tallycustomerapp.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import com.example.tallycustomerapp.data.AppDatabase
import com.example.tallycustomerapp.data.CompanyEntity
import com.example.tallycustomerapp.data.LedgerEntity
import com.example.tallycustomerapp.data.PageSnapshotInput
import com.example.tallycustomerapp.data.StockItemEntity
import com.example.tallycustomerapp.data.VoucherEntryInput
import com.example.tallycustomerapp.data.VoucherEntity
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

class WebAppInterface(
    private val context: Context,
    private val db: AppDatabase
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val chunkBuffers = ConcurrentHashMap<String, StringBuilder>()

    @JavascriptInterface
    fun setSyncStatus(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun beginSync(syncId: String) {
        if (syncId.isBlank()) return
        chunkBuffers[syncId] = StringBuilder()
    }

    @JavascriptInterface
    fun pushSyncChunk(syncId: String, chunk: String) {
        chunkBuffers[syncId]?.append(chunk)
    }

    @JavascriptInterface
    fun commitSync(syncId: String) {
        val buffer = chunkBuffers.remove(syncId) ?: return
        saveCompanyDataOffline(buffer.toString())
    }

    @JavascriptInterface
    fun beginPageSnapshot(syncId: String) {
        beginSync(syncId)
    }

    @JavascriptInterface
    fun pushPageSnapshotChunk(syncId: String, chunk: String) {
        pushSyncChunk(syncId, chunk)
    }

    @JavascriptInterface
    fun commitPageSnapshot(syncId: String) {
        val buffer = chunkBuffers.remove(syncId) ?: return
        savePageSnapshot(buffer.toString())
    }

    @JavascriptInterface
    fun savePageSnapshot(dataJson: String) {
        try {
            val payload = gson.fromJson(dataJson, PageSnapshotPayload::class.java)
                ?: throw JsonParseException("Empty page snapshot payload")
            require(payload.companyName.isNotBlank()) { "Company name is missing" }
            require(payload.url.isNotBlank()) { "Page URL is missing" }
            require(payload.html.isNotBlank()) { "Page HTML is empty" }

            scope.launch {
                runCatching {
                    val now = if (payload.capturedAt > 0) payload.capturedAt else System.currentTimeMillis()
                    val companyName = payload.companyName.trim()
                    val serial = payload.serialNumber.trim().ifEmpty { "name:${stableKey(companyName)}" }
                    val company = CompanyEntity(
                        companyName = companyName,
                        serialNumber = serial,
                        gstin = payload.gstin?.trim()?.takeIf { it.isNotEmpty() },
                        financialYearFrom = payload.financialYearFrom,
                        lastSynced = now
                    )
                    val htmlGzip = gzip(payload.html)
                    db.offlineDao().savePage(
                        company = company,
                        page = PageSnapshotInput(
                            pageKey = payload.pageKey.ifBlank { stableKey(payload.url) },
                            title = payload.title.trim(),
                            url = payload.url,
                            route = payload.route.ifBlank { payload.url },
                            htmlGzip = htmlGzip,
                            contentHash = payload.contentHash.ifBlank { stableKey(payload.html) },
                            capturedAt = now
                        )
                    )
                }.onSuccess {
                    mainHandler.post {
                        Toast.makeText(context, "Page saved offline", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { error ->
                    mainHandler.post {
                        Toast.makeText(
                            context,
                            "Page save failed: ${error.message ?: "database error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Invalid page snapshot: ${e.message ?: "invalid JSON"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @JavascriptInterface
    fun saveCompanyDataOffline(dataJson: String) {
        try {
            val payload = gson.fromJson(dataJson, CompanySyncPayload::class.java)
                ?: throw JsonParseException("Empty sync payload")
            require(payload.companyName.isNotBlank()) { "Company name is missing" }
            require(payload.serialNumber.isNotBlank()) { "Serial number is missing" }

            scope.launch {
                runCatching {
                    val now = System.currentTimeMillis()
                    val company = CompanyEntity(
                        companyName = payload.companyName.trim(),
                        serialNumber = payload.serialNumber.trim(),
                        gstin = payload.gstin?.trim()?.takeIf { it.isNotEmpty() },
                        financialYearFrom = payload.financialYearFrom,
                        lastSynced = now
                    )
                    val ledgers = payload.ledgers.map {
                        LedgerEntity(
                            companyId = 0,
                            guid = it.guid.ifBlank { "ledger:${it.name}" },
                            name = it.name.trim(),
                            parent = it.parent,
                            openingBalance = it.openingBalance,
                            closingBalance = it.closingBalance,
                            alteredOn = it.alteredOn
                        )
                    }
                    val vouchers = payload.vouchers.map {
                        VoucherEntity(
                            companyId = 0,
                            guid = it.guid.ifBlank { "voucher:${it.date}:${it.voucherNumber.orEmpty()}:${it.voucherType}:${it.partyName.orEmpty()}" },
                            date = it.date,
                            voucherType = it.voucherType,
                            voucherNumber = it.voucherNumber,
                            partyName = it.partyName,
                            amount = it.amount,
                            narration = it.narration,
                            alteredOn = it.alteredOn
                        )
                    }
                    val entries = payload.vouchers.associate { voucher ->
                        val guid = voucher.guid.ifBlank { "voucher:${voucher.date}:${voucher.voucherNumber.orEmpty()}:${voucher.voucherType}:${voucher.partyName.orEmpty()}" }
                        guid to voucher.entries.map { entry ->
                            VoucherEntryInput(
                                ledgerGuid = entry.ledgerGuid,
                                ledgerName = entry.ledgerName,
                                amount = entry.amount,
                                isDeemedPositive = entry.isDeemedPositive
                            )
                        }
                    }
                    val stocks = payload.stockItems.map {
                        StockItemEntity(
                            companyId = 0,
                            guid = it.guid.ifBlank { "stock:${it.name}" },
                            name = it.name.trim(),
                            parent = it.parent,
                            unit = it.unit,
                            openingQty = it.openingQty,
                            closingQty = it.closingQty,
                            openingValue = it.openingValue,
                            closingValue = it.closingValue,
                            closingRate = it.closingRate,
                            alteredOn = it.alteredOn
                        )
                    }

                    db.offlineDao().saveCollectedData(
                        company = company,
                        ledgers = ledgers,
                        vouchers = vouchers,
                        voucherEntriesByGuid = entries,
                        stockItems = stocks,
                        replaceAll = payload.replaceAll,
                        collectedSections = payload.collectedSections.toSet()
                    )
                }.onSuccess {
                    mainHandler.post {
                        Toast.makeText(context, "Tally data saved offline", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { error ->
                    mainHandler.post {
                        Toast.makeText(
                            context,
                            "Offline save failed: ${error.message ?: "database error"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } catch (e: Exception) {
            mainHandler.post {
                Toast.makeText(
                    context,
                    "Invalid sync data: ${e.message ?: "invalid JSON"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun gzip(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).bufferedWriter(Charsets.UTF_8).use { it.write(value) }
        return out.toByteArray()
    }

    private fun stableKey(value: String): String {
        var h = 2166136261L
        value.forEach { ch ->
            h = h xor ch.code.toLong()
            h = (h * 16777619L) and 0xffffffffL
        }
        return h.toString(16)
    }

    data class PageSnapshotPayload(
        val companyName: String,
        val serialNumber: String = "",
        val gstin: String? = null,
        val financialYearFrom: String? = null,
        val pageKey: String = "",
        val title: String = "",
        val url: String,
        val route: String = "",
        val contentHash: String = "",
        val capturedAt: Long = 0L,
        val html: String
    )

    data class CompanySyncPayload(
        val companyName: String,
        val serialNumber: String,
        val gstin: String? = null,
        val financialYearFrom: String? = null,
        val replaceAll: Boolean = false,
        val collectedSections: List<String> = emptyList(),
        val ledgers: List<LedgerPayload> = emptyList(),
        val vouchers: List<VoucherPayload> = emptyList(),
        val stockItems: List<StockPayload> = emptyList()
    )

    data class LedgerPayload(
        val guid: String = "",
        val name: String,
        val parent: String? = null,
        val openingBalance: Double = 0.0,
        val closingBalance: Double = 0.0,
        val alteredOn: Long? = null
    )

    data class VoucherPayload(
        val guid: String = "",
        val date: String,
        val voucherType: String,
        val voucherNumber: String? = null,
        val partyName: String? = null,
        val amount: Double = 0.0,
        val narration: String? = null,
        val alteredOn: Long? = null,
        val entries: List<VoucherEntryPayload> = emptyList()
    )

    data class VoucherEntryPayload(
        val ledgerGuid: String? = null,
        val ledgerName: String,
        val amount: Double = 0.0,
        val isDeemedPositive: Boolean = false
    )

    data class StockPayload(
        val guid: String = "",
        val name: String,
        val parent: String? = null,
        val unit: String? = null,
        val openingQty: Double = 0.0,
        val closingQty: Double = 0.0,
        val openingValue: Double = 0.0,
        val closingValue: Double = 0.0,
        val closingRate: Double = 0.0,
        val alteredOn: Long? = null
    )
}

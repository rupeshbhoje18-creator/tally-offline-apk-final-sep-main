package com.example.tallycustomerapp.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "page_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = CompanyEntity::class,
            parentColumns = ["id"],
            childColumns = ["companyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("companyId"),
        Index(value = ["companyId", "pageKey"], unique = true),
        Index(value = ["companyId", "capturedAt"])
    ]
)
data class PageSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val companyId: Long,
    val pageKey: String,
    val title: String,
    val url: String,
    val route: String,
    val htmlGzip: ByteArray,
    val contentHash: String,
    val capturedAt: Long
)

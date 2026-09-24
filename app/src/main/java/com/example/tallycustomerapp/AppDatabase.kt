package com.example.tallycustomerapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CompanyEntity::class,
        LedgerEntity::class,
        VoucherEntity::class,
        VoucherEntryEntity::class,
        StockItemEntity::class,
        SyncStateEntity::class,
        PageSnapshotEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun offlineDao(): OfflineDao
    abstract fun companyDao(): CompanyDao

    companion object {
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """CREATE TABLE IF NOT EXISTS page_snapshots (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        companyId INTEGER NOT NULL,
                        pageKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        url TEXT NOT NULL,
                        route TEXT NOT NULL,
                        htmlGzip BLOB NOT NULL,
                        contentHash TEXT NOT NULL,
                        capturedAt INTEGER NOT NULL,
                        FOREIGN KEY(companyId) REFERENCES companies(id) ON DELETE CASCADE
                    )"""
                )
                database.execSQL("CREATE INDEX IF NOT EXISTS index_page_snapshots_companyId ON page_snapshots(companyId)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_page_snapshots_companyId_pageKey ON page_snapshots(companyId, pageKey)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_page_snapshots_companyId_capturedAt ON page_snapshots(companyId, capturedAt)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tally_offline_final.db"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

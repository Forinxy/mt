package com.example.mtsignin.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AccountEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    companion object {
        /**
         * v1 -> v2: accounts 表新增 lastToken 列（存储最近一次登录获取的会话 token）
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN lastToken TEXT")
            }
        }

        /**
         * v2 -> v3: accounts 表新增 lastRankingQueryDate 列（最近一次查询排名的日期），
         * 用于同一天内"签到/查排行只执行一次"，结果记录在本地以降低请求频率、防止触发风控
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN lastRankingQueryDate TEXT")
            }
        }
    }
}
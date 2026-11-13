package com.guanglu.caliper.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.guanglu.caliper.core.database.dao.BatchDao
import com.guanglu.caliper.core.database.dao.DeviceDao
import com.guanglu.caliper.core.database.dao.MeasurementDao
import com.guanglu.caliper.core.database.dao.TemplateDao
import com.guanglu.caliper.core.database.entity.AppMeta
import com.guanglu.caliper.core.database.entity.Batch
import com.guanglu.caliper.core.database.entity.Device
import com.guanglu.caliper.core.database.entity.Measurement
import com.guanglu.caliper.core.database.entity.Template

/**
 * 禁 fallbackToDestructiveMigration()：卡尺不保存历史数据，破坏性迁移等于永久丢失检验记录（A9）。
 * v1 = 第 6 节的原始表结构；v2 = 追加通道号、历史通道标记与 device 的 5 个配置登记字段；
 * v3 = 数值单位由整数微米改为 0.1 微米（10^-4 mm），存量数值 ×10 保持物理长度不变。
 */
@Database(
    entities = [Measurement::class, Batch::class, Template::class, Device::class, AppMeta::class],
    version = 3,
    exportSchema = false
)
/**
 * Room 数据库定义与版本迁移。
 *
 * @author AI-Generated
 */
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao
    abstract fun batchDao(): BatchDao
    abstract fun templateDao(): TemplateDao
    abstract fun deviceDao(): DeviceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `measurement` ADD COLUMN `channel_no` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `measurement` ADD COLUMN `legacy_channel` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `device` ADD COLUMN `channel_no` INTEGER")
                db.execSQL("ALTER TABLE `device` ADD COLUMN `custom_name` TEXT")
                db.execSQL("ALTER TABLE `device` ADD COLUMN `auto_emit_seconds` INTEGER")
                db.execSQL("ALTER TABLE `device` ADD COLUMN `sleep_enabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `device` ADD COLUMN `sleep_minutes` INTEGER")
                db.execSQL("ALTER TABLE `device` ADD COLUMN `terminator` TEXT NOT NULL DEFAULT 'CR'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE `measurement` SET `value_um` = `value_um` * 10")
                db.execSQL("UPDATE `batch` SET `nominal_um` = `nominal_um` * 10 WHERE `nominal_um` IS NOT NULL")
                db.execSQL(
                    "UPDATE `batch` SET `lower_limit_um` = `lower_limit_um` * 10 WHERE `lower_limit_um` IS NOT NULL"
                )
                db.execSQL(
                    "UPDATE `batch` SET `upper_limit_um` = `upper_limit_um` * 10 WHERE `upper_limit_um` IS NOT NULL"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "gl_caliper.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}

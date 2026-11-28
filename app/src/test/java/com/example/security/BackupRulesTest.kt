package com.example.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PhoneAgentDatabase
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRulesTest {

    private fun findXmlFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("app", relativePath),
            File("../app", relativePath)
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not locate XML file $relativePath in any candidate path")
    }

    @Test
    fun `backup_rules xml explicitly excludes database sharedpref file root and external`() {
        val file = findXmlFile("src/main/res/xml/backup_rules.xml")
        val content = file.readText()

        assertTrue(content.contains("<exclude domain=\"database\""))
        assertTrue(content.contains("<exclude domain=\"sharedpref\""))
        assertTrue(content.contains("<exclude domain=\"file\""))
        assertTrue(content.contains("<exclude domain=\"root\""))
        assertTrue(content.contains("<exclude domain=\"external\""))
    }

    @Test
    fun `data_extraction_rules xml explicitly excludes database sharedpref file root and external from cloud-backup and device-transfer`() {
        val file = findXmlFile("src/main/res/xml/data_extraction_rules.xml")
        val content = file.readText()

        assertTrue("Must contain cloud-backup section", content.contains("<cloud-backup>"))
        assertTrue("Must contain device-transfer section", content.contains("<device-transfer>"))

        // تحقق من استبعاد كافة النطاقات الحساسة من النسخ السحابي
        val cloudSection = content.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        assertTrue(cloudSection.contains("<exclude domain=\"database\""))
        assertTrue(cloudSection.contains("<exclude domain=\"sharedpref\""))
        assertTrue(cloudSection.contains("<exclude domain=\"file\""))
        assertTrue(cloudSection.contains("<exclude domain=\"root\""))
        assertTrue(cloudSection.contains("<exclude domain=\"external\""))

        // تحقق من استبعاد كافة النطاقات الحساسة من النقل المباشر بين الأجهزة
        val transferSection = content.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        assertTrue(transferSection.contains("<exclude domain=\"database\""))
        assertTrue(transferSection.contains("<exclude domain=\"sharedpref\""))
        assertTrue(transferSection.contains("<exclude domain=\"file\""))
        assertTrue(transferSection.contains("<exclude domain=\"root\""))
        assertTrue(transferSection.contains("<exclude domain=\"external\""))
    }

    @Test
    fun `user_memory habit_logs and conversation_summaries reside in excluded local Room database`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = PhoneAgentDatabase.getInstance(context)
        val sqlite = db.openHelper.writableDatabase
        val tables = mutableSetOf<String>()
        sqlite.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
            while (cursor.moveToNext()) {
                tables.add(cursor.getString(0))
            }
        }
        assertTrue("user_memory must exist in Room DB excluded from backup", "user_memory" in tables)
        assertTrue("habit_logs must exist in Room DB excluded from backup", "habit_logs" in tables)
        assertTrue("conversation_summaries must exist in Room DB excluded from backup", "conversation_summaries" in tables)

        // التحقق من ترحيل الإصدار 3 إلى 4 لإنشاء جدول ملخصات المحادثة بأمان
        val tempHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val memDb = tempHelper.writableDatabase
        PhoneAgentDatabase.MIGRATION_3_4.migrate(memDb)
        val migratedTables = mutableSetOf<String>()
        memDb.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            while (c.moveToNext()) migratedTables.add(c.getString(0))
        }
        assertTrue("conversation_summaries" in migratedTables)
        tempHelper.close()
    }
}

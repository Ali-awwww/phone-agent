package com.example.security

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

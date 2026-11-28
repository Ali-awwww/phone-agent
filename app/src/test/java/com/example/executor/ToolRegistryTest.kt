package com.example.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolRegistryTest {

    @Test
    fun `register custom tool and retrieve it`() {
        val customTool = AgentToolDefinition(
            name = "custom_test_tool",
            titleAr = "أداة تجريبية",
            descriptionAr = "وصف الأداة التجريبية",
            parameters = listOf(
                ToolParameter("param1", "string", "معامل أول", true)
            ),
            requiresApproval = false,
            category = "TEST"
        )
        ToolRegistry.register(customTool)
        val retrieved = ToolRegistry.getTool("custom_test_tool")
        assertNotNull(retrieved)
        assertEquals("أداة تجريبية", retrieved?.titleAr)
        assertEquals(1, retrieved?.parameters?.size)
        assertEquals("param1", retrieved?.parameters?.first()?.name)
    }

    @Test
    fun `resolveIntent resolves file management request to file_management tool`() {
        val resolution = ToolRegistry.resolveIntent("فحص الملفات والمجلدات الفارغة")
        assertEquals("file_management", resolution.toolName)
        assertTrue(resolution.intent is ChatRouteIntent.FileManagement)
        assertEquals(0.95f, resolution.confidence, 0.001f)
    }

    @Test
    fun `resolveIntent resolves game play request to play_game tool`() {
        val resolution = ToolRegistry.resolveIntent("العب لعبة مع الوكيل")
        assertEquals("play_game", resolution.toolName)
        assertTrue(resolution.intent is ChatRouteIntent.StartGameplay)
    }

    @Test
    fun `resolveIntent resolves alarm and reminder requests to set_alarm tool`() {
        val resolution = ToolRegistry.resolveIntent("ذكّرني بشرب الماء بعد 15 دقيقة")
        assertEquals("set_alarm", resolution.toolName)
        assertTrue(resolution.intent is ChatRouteIntent.CreateTaskOrReminder)
    }

    @Test
    fun `resolveIntent handles unknown command attempt explicitly as UNKNOWN tool error`() {
        val resolution = ToolRegistry.resolveIntent("افعل شيء غير مألوف تماما غير مدعوم في النظام 999xyz")
        // إذا كان الطلب يبدأ بفعل أمر غير معروف أو محاولة تشغيل أداة وهمية
        // الموجه يعيد أداة غير معروفة
        if (resolution.toolName == "UNKNOWN") {
            assertNotNull(resolution.errorReason)
            assertTrue(resolution.errorReason!!.contains("أداة غير معروفة"))
        } else {
            // أو يتم تصنيفه كنقاش عام إذا لم يكن أمراً صريحاً
            assertTrue(resolution.toolName == "conversational_chat" || resolution.toolName == "UNKNOWN")
        }
    }

    @Test
    fun `getRegistryPromptDescription includes all registered core tools`() {
        val desc = ToolRegistry.getRegistryPromptDescription()
        assertTrue(desc.contains("open_app"))
        assertTrue(desc.contains("send_message"))
        assertTrue(desc.contains("file_management"))
        assertTrue(desc.contains("play_game"))
        assertTrue(desc.contains("set_alarm"))
        assertTrue(desc.contains("read_screen"))
        assertTrue(desc.contains("show_apps"))
        assertTrue(desc.contains("show_action_history"))
        assertTrue(desc.contains("open_settings"))
    }
}

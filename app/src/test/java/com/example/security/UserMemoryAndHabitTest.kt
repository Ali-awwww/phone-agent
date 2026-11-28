package com.example.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.PhoneAgentDatabase
import com.example.data.local.UserMemoryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserMemoryAndHabitTest {

    @Test
    fun `persistent user memory stores facts, forgets them, and clears memory (S2-1)`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = PhoneAgentDatabase.getInstance(context)
        val repo = UserMemoryRepository(db.userMemoryDao(), db.habitLogDao())

        repo.clearMemory()
        assertTrue(repo.getAllMemories().isEmpty())

        val id1 = repo.rememberFact("لعبتي المفضلة هي الشطرنج", "CONVERSATION", 0.95f)
        val id2 = repo.rememberFact("أفضل وضع الشاشة ليلياً", "PREFERENCE", 1.0f)

        val memories = repo.getAllMemories()
        assertEquals(2, memories.size)
        assertTrue(memories.any { it.fact.contains("الشطرنج") })

        repo.forgetFact(id1)
        val afterForget = repo.getAllMemories()
        assertEquals(1, afterForget.size)
        assertFalse(afterForget.any { it.fact.contains("الشطرنج") })

        repo.clearMemory()
        assertTrue(repo.getAllMemories().isEmpty())
    }

    @Test
    fun `habit logging records user habits locally (S2-3)`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = PhoneAgentDatabase.getInstance(context)
        val repo = UserMemoryRepository(db.userMemoryDao(), db.habitLogDao())

        repo.recordHabit("الروتين الصباحي", 7, 1)
        val habits = repo.getTopHabits()
        assertNotNull(habits)
        assertTrue(habits.any { it.actionName == "الروتين الصباحي" })
    }

    @Test
    fun `policy rejection provides simple friendly explanation and alternative (S2-5d)`() {
        val (explanation, alternative) = UserMemoryRepository.explainPolicyRejection("POLICY_BLOCKED_SENSITIVE")
        assertNotNull(explanation)
        assertNotNull(alternative)
        assertTrue(explanation.contains("سياسات الأمان"))
        assertTrue(alternative.contains("بدلاً من ذلك"))
    }
}

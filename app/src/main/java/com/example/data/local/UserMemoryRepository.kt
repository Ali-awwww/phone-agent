package com.example.data.local

import kotlinx.coroutines.flow.Flow

class UserMemoryRepository(
    private val memoryDao: UserMemoryDao,
    private val habitDao: HabitLogDao
) {
    val allMemories: Flow<List<UserMemoryEntity>> = memoryDao.observeAllMemories()

    suspend fun rememberFact(fact: String, source: String = "CONVERSATION", confidence: Float = 1.0f): Long {
        return memoryDao.insertMemory(
            UserMemoryEntity(
                fact = fact,
                source = source,
                confidence = confidence
            )
        )
    }

    suspend fun forgetFact(id: Long) {
        memoryDao.deleteMemory(id)
    }

    suspend fun clearMemory() {
        memoryDao.clearAllMemories()
    }

    suspend fun getAllMemories(): List<UserMemoryEntity> {
        return memoryDao.getAllMemories()
    }

    suspend fun recordHabit(actionName: String, hourOfDay: Int, dayOfWeek: Int) {
        habitDao.insertHabit(
            HabitLogEntity(
                actionName = actionName,
                hourOfDay = hourOfDay,
                dayOfWeek = dayOfWeek,
                frequencyCount = 1,
                lastTimestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun getTopHabits(): List<HabitLogEntity> {
        return habitDao.getAllHabits()
    }

    fun formatMemoryContext(): String {
        return "الذاكرة الدائمة وتفضيلات المستخدم نشطة ومحفوظة محلياً."
    }

    companion object {
        fun explainPolicyRejection(actionType: String): Pair<String, String> {
            return when {
                actionType.contains("POLICY_BLOCKED") ->
                    "عذراً، لا يمكن تنفيذ هذا الإجراء لأنه يتعارض مع سياسات الأمان وحماية الخصوصية." to
                    "يمكنك بدلاً من ذلك تنفيذ الإجراء يدويًا بموافقتك الشخصية."
                else ->
                    "عذراً، تعذر إتمام هذا الإجراء." to
                    "يرجى التحقق من الأذونات أو تجربة أمر آخر."
            }
        }
    }
}

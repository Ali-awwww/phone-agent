package com.example.executor

import com.example.data.local.AgentTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCancellationResolverTest {

    private val tasks = listOf(
        AgentTaskEntity(id = 1L, title = "دواء الصباح", taskType = "REMINDER"),
        AgentTaskEntity(id = 2L, title = "دواء المساء", taskType = "REMINDER"),
        AgentTaskEntity(id = 3L, title = "موعد الاجتماع", taskType = "TASK")
    )

    @Test
    fun `blank query never silently picks last task and asks for clarification`() {
        val result = TaskCancellationResolver.resolve(queryText = "", activeTasks = tasks)
        assertTrue(result is TaskCancelResolution.NeedsClarification)
        val clarification = result as TaskCancelResolution.NeedsClarification
        assertEquals(3, clarification.candidateTasks.size)
    }

    @Test
    fun `ambiguous partial query lists matching candidates instead of canceling first silently`() {
        val result = TaskCancellationResolver.resolve(queryText = "دواء", activeTasks = tasks)
        assertTrue(result is TaskCancelResolution.NeedsClarification)
        val clarification = result as TaskCancelResolution.NeedsClarification
        assertEquals(2, clarification.candidateTasks.size)
    }

    @Test
    fun `unique match requests explicit confirmation instead of immediate silent deletion`() {
        val result = TaskCancellationResolver.resolve(queryText = "الاجتماع", activeTasks = tasks)
        assertTrue(result is TaskCancelResolution.NeedsConfirmation)
        val confirmation = result as TaskCancelResolution.NeedsConfirmation
        assertEquals(3L, confirmation.targetTask.id)
    }

    @Test
    fun `empty active list returns NoActiveTasks`() {
        val result = TaskCancellationResolver.resolve(queryText = "الاجتماع", activeTasks = emptyList())
        assertTrue(result is TaskCancelResolution.NoActiveTasks)
    }
}

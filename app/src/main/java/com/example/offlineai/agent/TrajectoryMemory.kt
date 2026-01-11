package com.example.offlineai.agent.model

import android.graphics.Bitmap

/**
 * Trajectory Memory - stores agent execution history
 * Based on MAI-UI unified_memory.py
 */
data class TrajectoryStep(
    val stepIndex: Int,
    val screenshot: Bitmap?,
    val thinking: String,
    val action: AgentAction,
    val executionResult: ExecutionResult,
    val timestamp: Long = System.currentTimeMillis(),
    val coordinateError: String? = null,  // Error message if coordinate was out of range
    val rawModelOutput: String = ""  // Complete model output including <thinking> and <tool_call> tags for Previous Steps
)

class TrajectoryMemory(
    private val maxHistorySteps: Int = 5
) {
    var taskGoal: String = ""
        private set
    
    private val steps = mutableListOf<TrajectoryStep>()
    
    fun setTaskGoal(goal: String) {
        taskGoal = goal
    }
    
    fun addStep(step: TrajectoryStep) {
        steps.add(step)
    }
    
    fun getRecentSteps(count: Int = maxHistorySteps): List<TrajectoryStep> {
        return if (steps.size <= count) {
            steps.toList()
        } else {
            steps.takeLast(count)
        }
    }
    
    fun getAllSteps(): List<TrajectoryStep> = steps.toList()
    
    fun getStepCount(): Int = steps.size
    
    fun getLastStep(): TrajectoryStep? = steps.lastOrNull()

    fun updateLastStep(step: TrajectoryStep) {
        if (steps.isEmpty()) {
            return
        }
        steps[steps.size - 1] = step
    }
    
    fun clear() {
        taskGoal = ""
        steps.clear()
    }
    
    fun isTaskComplete(): Boolean {
        val lastStep = steps.lastOrNull() ?: return false
        return lastStep.action is AgentAction.Terminate
    }
    
    fun getTaskStatus(): String {
        return when {
            steps.isEmpty() -> "未开始"
            isTaskComplete() -> {
                val lastAction = steps.last().action as AgentAction.Terminate
                if (lastAction.status == AgentAction.Terminate.Status.SUCCESS) {
                    "已完成"
                } else {
                    "失败"
                }
            }
            else -> "执行中 (${steps.size} 步)"
        }
    }
}

package com.omni.assistant.data

sealed class AgentStatus {
    object Idle : AgentStatus()
    object WakeWordListening : AgentStatus()
    object VoiceListening : AgentStatus()
    data class Processing(val goal: String) : AgentStatus()
    data class Executing(val goal: String, val step: Int, val maxSteps: Int, val lastAction: String) : AgentStatus()
    data class Speaking(val text: String) : AgentStatus()
    data class Done(val success: Boolean, val reason: String) : AgentStatus()
    data class Error(val message: String) : AgentStatus()
}

data class AgentStep(
    val stepNumber: Int,
    val screenElements: List<ScreenElement>,
    val thinking: String,
    val action: AgentAction,
    val result: String? = null
)

data class ScreenElement(
    val id: String,
    val type: String,
    val text: String?,
    val contentDescription: String?,
    val bounds: Rect?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean,
    val children: List<ScreenElement> = emptyList()
)

data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

sealed class AgentAction {
    data class Tap(val nodeId: String? = null, val x: Int? = null, val y: Int? = null) : AgentAction()
    data class TypeText(val text: String, val nodeId: String? = null) : AgentAction()
    data class Swipe(val direction: String) : AgentAction()
    data class Scroll(val direction: String, val nodeId: String? = null) : AgentAction()
    object PressBack : AgentAction()
    object PressHome : AgentAction()
    object PressRecents : AgentAction()
    data class OpenApp(val packageName: String, val name: String? = null) : AgentAction()
    data class OpenUrl(val url: String) : AgentAction()
    data class Wait(val ms: Long) : AgentAction()
    data class Done(val success: Boolean, val reason: String) : AgentAction()
}

package com.photonlab.domain

import com.photonlab.domain.model.EditState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val MAX_HISTORY = 50

/**
 * Manages an undo/redo stack of [EditState] snapshots.
 *
 * Consecutive pushes with the same non-null [paramKey] are **compressed** into a single
 * history entry (the last value wins). This prevents slider drags from flooding the stack.
 */
class EditHistoryManager {

    private val stack = ArrayDeque<EditState>()
    private var cursor = -1
    private var lastParamKey: String? = null

    private val _currentState = MutableStateFlow(EditState())
    val currentState: StateFlow<EditState> = _currentState.asStateFlow()

    val canUndo get() = cursor > 0
    val canRedo get() = cursor < stack.size - 1

    /** All states in the history stack (oldest first). */
    val entries: List<EditState> get() = stack.toList()

    /** Index of the currently active state within [entries]. */
    val currentIndex: Int get() = cursor

    /** Replace current state without recording history (e.g. loading a new image). */
    fun reset(state: EditState = EditState()) {
        lastParamKey = null
        stack.clear()
        stack.addLast(state)
        cursor = 0
        _currentState.value = state
    }

    /**
     * Push a new state onto the history stack.
     *
     * If [paramKey] is non-null and matches the key of the previous push *at the current
     * cursor position*, the top entry is replaced instead of a new one being added
     * (slider-drag compression).
     */
    fun push(state: EditState, paramKey: String? = null) {
        if (paramKey != null && paramKey == lastParamKey && cursor == stack.size - 1) {
            stack[cursor] = state
            _currentState.value = state
            return
        }
        lastParamKey = paramKey
        while (stack.size - 1 > cursor) stack.removeLast()
        stack.addLast(state)
        if (stack.size > MAX_HISTORY) {
            stack.removeFirst()
        } else {
            cursor++
        }
        _currentState.value = state
    }

    fun undo(): EditState? {
        if (!canUndo) return null
        lastParamKey = null
        cursor--
        val state = stack[cursor]
        _currentState.value = state
        return state
    }

    fun redo(): EditState? {
        if (!canRedo) return null
        lastParamKey = null
        cursor++
        val state = stack[cursor]
        _currentState.value = state
        return state
    }

    /** Jump directly to a specific history index. Returns the state, or null if index invalid. */
    fun jumpTo(index: Int): EditState? {
        if (index < 0 || index >= stack.size) return null
        lastParamKey = null
        cursor = index
        val state = stack[cursor]
        _currentState.value = state
        return state
    }
}

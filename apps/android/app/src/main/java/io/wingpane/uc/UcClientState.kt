package io.wingpane.uc

import io.wingpane.proto.UcEdge

/** Pure client-side UC transitions. Edges name the Mac edge, not the tablet edge. */
sealed interface UcClientState {
    data object Idle : UcClientState
    data class Armed(val edge: UcEdge) : UcClientState
    data class Inside(val edge: UcEdge) : UcClientState
}

sealed interface UcClientEvent {
    data class Config(val edge: UcEdge) : UcClientEvent
    data class Enter(val edge: UcEdge) : UcClientEvent
    data object Exit : UcClientEvent
    data object Disconnected : UcClientEvent
}

enum class UcClientEffect { CancelAll, ShowCursor, HideCursor }

data class UcClientTransition(val state: UcClientState, val effects: List<UcClientEffect> = emptyList())

fun reduceUc(state: UcClientState, event: UcClientEvent): UcClientTransition = when (event) {
    is UcClientEvent.Config -> when (state) {
        UcClientState.Idle -> UcClientTransition(UcClientState.Armed(event.edge))
        is UcClientState.Armed -> UcClientTransition(UcClientState.Armed(event.edge))
        is UcClientState.Inside -> UcClientTransition(UcClientState.Inside(event.edge))
    }
    is UcClientEvent.Enter -> UcClientTransition(UcClientState.Inside(event.edge),
        if (state is UcClientState.Inside) listOf(UcClientEffect.CancelAll, UcClientEffect.ShowCursor)
        else listOf(UcClientEffect.ShowCursor))
    UcClientEvent.Exit -> when (state) {
        is UcClientState.Inside -> UcClientTransition(UcClientState.Armed(state.edge), listOf(UcClientEffect.CancelAll, UcClientEffect.HideCursor))
        else -> UcClientTransition(state)
    }
    UcClientEvent.Disconnected -> UcClientTransition(UcClientState.Idle,
        if (state is UcClientState.Inside) listOf(UcClientEffect.CancelAll, UcClientEffect.HideCursor) else emptyList())
}

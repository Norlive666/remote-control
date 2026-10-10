package io.github.norlive.remotecontrol

data class PanelPosition(val x: Float, val y: Float)

data class PanelOffset(val x: Float, val y: Float)

fun panelOffset(
    position: PanelPosition,
    canvasWidth: Float,
    canvasHeight: Float,
    itemWidth: Float,
    itemHeight: Float,
): PanelOffset = PanelOffset(
    position.x.coerceIn(0f, 1f) * (canvasWidth - itemWidth).coerceAtLeast(0f),
    position.y.coerceIn(0f, 1f) * (canvasHeight - itemHeight).coerceAtLeast(0f),
)

fun movePanelItem(
    position: PanelPosition,
    dx: Float,
    dy: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    itemWidth: Float,
    itemHeight: Float,
): PanelPosition {
    val availableWidth = (canvasWidth - itemWidth).coerceAtLeast(0f)
    val availableHeight = (canvasHeight - itemHeight).coerceAtLeast(0f)
    return PanelPosition(
        if (availableWidth == 0f) 0f else (position.x + dx / availableWidth).coerceIn(0f, 1f),
        if (availableHeight == 0f) 0f else (position.y + dy / availableHeight).coerceIn(0f, 1f),
    )
}

class LayoutEditSession(saved: Map<String, PanelPosition> = emptyMap()) {
    var saved: Map<String, PanelPosition> = saved.toMap()
        private set
    var draft: Map<String, PanelPosition> = emptyMap()
        private set
    var editing: Boolean = false
        private set
    val positions: Map<String, PanelPosition> get() = if (editing) draft else saved

    fun begin() {
        draft = saved.toMap()
        editing = true
    }

    fun move(id: String, position: PanelPosition) {
        if (!editing) return
        draft = draft + (id to PanelPosition(position.x.coerceIn(0f, 1f), position.y.coerceIn(0f, 1f)))
    }

    fun save() {
        if (!editing) return
        saved = draft
        draft = emptyMap()
        editing = false
    }

    fun cancel() {
        draft = emptyMap()
        editing = false
    }

    fun restoreDefaults() {
        if (editing) draft = emptyMap()
    }
}

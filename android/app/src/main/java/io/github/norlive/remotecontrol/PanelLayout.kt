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

class LayoutEditSession(saved: Map<String, PanelPosition> = emptyMap(),savedScales: Map<String, Float> = emptyMap()) {
    var savedScales: Map<String, Float> = savedScales.toMap(); private set
    private var draftScales: Map<String, Float> = emptyMap()
    val scales: Map<String, Float> get() = if(editing)draftScales else savedScales
    fun resize(id: String,scale: Float) { if(editing && scale.isFinite())draftScales=draftScales+(id to scale.coerceIn(.5f,1.5f)) }
    var saved: Map<String, PanelPosition> = saved.toMap()
        private set
    var draft: Map<String, PanelPosition> = emptyMap()
        private set
    var editing: Boolean = false
        private set
    val positions: Map<String, PanelPosition> get() = if (editing) draft else saved

    fun begin() {
        draftScales = savedScales.toMap()
        draft = saved.toMap()
        editing = true
    }

    fun move(id: String, position: PanelPosition) {
        if (!editing) return
        draft = draft + (id to PanelPosition(position.x.coerceIn(0f, 1f), position.y.coerceIn(0f, 1f)))
    }

    fun save() {
        if (!editing) return
        savedScales = draftScales
        draftScales = emptyMap()
        saved = draft
        draft = emptyMap()
        editing = false
    }

    fun cancel() {
        draftScales = emptyMap()
        draft = emptyMap()
        editing = false
    }

    fun restoreDefaults() {
        if (editing) { draft = emptyMap();draftScales = emptyMap() }
    }
}

package dev.nattle.packsanity.feature.packruntime.domain.model

data class TrackerMapView(
    val title: String,
    val mapNames: List<String> = emptyList(),
    val children: List<TrackerMapView> = emptyList(),
)

data class TrackerItemLayoutGroup(
    val title: String? = null,
    val rows: List<List<String>>,
    val itemSize: Int? = null,
    /** Supplemental pack page geometry; ordinary inventory groups retain the card renderer. */
    val grid: TrackerItemGrid? = null,
    val tabGroupId: String? = null,
    val tabGroupTitle: String? = null,
    val tabTitle: String? = null,
    val tabPath: List<TrackerItemTab> = emptyList(),
)

data class TrackerItemTab(
    val groupId: String,
    val groupTitle: String?,
    val id: String,
    val title: String?,
    val index: Int,
)

data class TrackerItemGrid(
    val width: Int,
    val height: Int,
    val cells: List<TrackerItemGridCell>,
    val frozenColumnsWidth: Int = 0,
    val frozenRowsHeight: Int = 0,
    val backgrounds: List<TrackerItemGridBackground> = emptyList(),
    /** Background owned by this widget, resized with it during array composition. */
    val widgetBackgroundIndex: Int? = null,
    val isCanvas: Boolean = false,
    val labels: List<TrackerItemGridLabel> = emptyList(),
)

data class TrackerItemGridLabel(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val text: String,
)

/** A layout widget's background covers its content and item padding, before outer margins. */
data class TrackerItemGridBackground(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val color: String,
)

data class TrackerItemGridCell(
    val code: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val background: String? = null,
    val depth: Int = 0,
)

fun List<TrackerMapView>.flattenMapViews(): List<TrackerMapView> = flatMap { view ->
    listOf(view) + view.children.flattenMapViews()
}

fun List<TrackerMapView>.mapNamesDepthFirst(): List<String> = flatMap { view ->
    view.mapNames + view.children.mapNamesDepthFirst()
}

data class LoadedTrackerPackage(
    val pack: TrackerPack,
    val maps: List<TrackerMap>,
    val items: List<TrackerItem>,
    val locations: List<TrackerLocation>,
    val mapViews: List<TrackerMapView>,
    val itemLayoutRows: List<List<String>> = emptyList(),
    val itemLayoutGroups: List<TrackerItemLayoutGroup> = emptyList(),
    val packSettingsLayoutRows: List<List<String>> = emptyList(),
    val diagnostics: List<String>,
    val archipelagoItemCodesById: Map<Long, Set<String>> = emptyMap(),
    val movementMapNamesByRoom: Map<String, String> = emptyMap(),
    val presentationSettings: TrackerPresentationSettings = TrackerPresentationSettings(),
    /** The resolved tracker roots intentionally contain no map widgets. */
    val hasExplicitMaplessTrackerRoot: Boolean = false,
)

data class TrackerPresentationSettings(
    val smoothScaling: Boolean = false,
    val smoothMapScaling: Boolean = true,
    val disabledImageFilter: String = "grey",
)

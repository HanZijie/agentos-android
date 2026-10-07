package org.agentos.sample.notes.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.agentos.sample.notes.R
import org.agentos.sample.notes.data.Note
import org.agentos.sample.notes.data.NoteColor
import org.agentos.sample.notes.ui.HomeUi
import org.agentos.sample.notes.ui.NotesViewModel
import org.agentos.sample.notes.ui.Scope
import org.agentos.sample.notes.ui.components.ColorPickerSheet
import org.agentos.sample.notes.ui.components.ConfirmDialog
import org.agentos.sample.notes.ui.components.EmptyKind
import org.agentos.sample.notes.ui.components.EmptyState
import org.agentos.sample.notes.ui.components.NotesSnackbar

@Composable
fun HomeScreen(vm: NotesViewModel, ui: HomeUi, grid: Boolean, snackbar: SnackbarHostState) {
    val scope = ui.scope
    var colorTarget by remember { mutableStateOf<Note?>(null) }
    var deleteTarget by remember { mutableStateOf<Note?>(null) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) { NotesSnackbar(it) } },
        floatingActionButton = {
            AnimatedVisibility(
                visible = scope == Scope.NOTES,
                enter = scaleIn(tween(180)) + fadeIn(tween(180)),
                exit = scaleOut(tween(120)) + fadeOut(tween(120)),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { vm.openEditor(null) },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(stringResource(R.string.home_new_note), style = MaterialTheme.typography.labelLarge) },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        bottomBar = { HomeNavBar(scope, ui.counts, vm::selectScope) },
    ) { padding ->
        HomeContent(
            vm = vm,
            ui = ui,
            grid = grid,
            padding = padding,
            onColor = { colorTarget = it },
            onDelete = { deleteTarget = it },
            onEmptyTrash = { confirmEmptyTrash = true },
        )
    }

    colorTarget?.let { note ->
        // 以最新的数据为准（MCP 可能刚改过颜色）
        val current = ui.pinned.plus(ui.others).firstOrNull { it.id == note.id } ?: note
        ColorPickerSheet(
            selected = current.color,
            onPick = { color: NoteColor ->
                vm.setColor(note.id, color)
                colorTarget = null
            },
            onDismiss = { colorTarget = null },
        )
    }
    deleteTarget?.let { note ->
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_title),
            message = stringResource(R.string.dialog_delete_message, note.displayTitle.ifEmpty { stringResource(R.string.editor_untitled) }),
            confirmText = stringResource(R.string.dialog_delete_confirm),
            cancelText = stringResource(R.string.action_cancel),
            onConfirm = {
                vm.deleteForever(note.id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
    if (confirmEmptyTrash) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_empty_trash_title),
            message = pluralStringResource(R.plurals.dialog_empty_trash_message, ui.counts[Scope.TRASH] ?: 0, ui.counts[Scope.TRASH] ?: 0),
            confirmText = stringResource(R.string.dialog_empty_trash_confirm),
            cancelText = stringResource(R.string.action_cancel),
            onConfirm = {
                vm.emptyTrash()
                confirmEmptyTrash = false
            },
            onDismiss = { confirmEmptyTrash = false },
        )
    }
}

@Composable
private fun HomeNavBar(selected: Scope, counts: Map<Scope, Int>, onSelect: (Scope) -> Unit) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        val items = listOf(
            Triple(Scope.NOTES, R.string.tab_notes, Icons.AutoMirrored.Outlined.StickyNote2 to Icons.AutoMirrored.Rounded.StickyNote2),
            Triple(Scope.ARCHIVE, R.string.tab_archive, Icons.Outlined.Inventory2 to Icons.Rounded.Inventory2),
            Triple(Scope.TRASH, R.string.tab_trash, Icons.Outlined.Delete to Icons.Rounded.Delete),
        )
        items.forEach { (scope, label, icons) ->
            val isSelected = scope == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(scope) },
                icon = { Icon(if (isSelected) icons.second else icons.first, null) },
                label = { Text(stringResource(label), style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun HomeContent(
    vm: NotesViewModel,
    ui: HomeUi,
    grid: Boolean,
    padding: PaddingValues,
    onColor: (Note) -> Unit,
    onDelete: (Note) -> Unit,
    onEmptyTrash: () -> Unit,
) {
    val scope = ui.scope
    val columns = if (!grid) 1 else if (LocalConfiguration.current.screenWidthDp >= 600) 3 else 2
    val gridState = rememberLazyStaggeredGridState()
    LaunchedEffect(scope, ui.tagFilter) { gridState.scrollToItem(0) }

    fun card(note: Note): @Composable (Modifier) -> Unit = { modifier ->
        NoteCard(
            note = note,
            scope = scope,
            grid = grid,
            onOpen = { vm.openEditor(note.id) },
            onPin = { vm.setPinned(note.id, !note.pinned) },
            onColor = { onColor(note) },
            onArchive = { vm.archive(note.id) },
            onUnarchive = { vm.unarchive(note.id) },
            onTrash = { vm.trash(note.id) },
            onRestore = { vm.restore(note.id) },
            onDeleteForever = { onDelete(note) },
            modifier = modifier,
        )
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        state = gridState,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = padding.calculateBottomPadding() + 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine) {
            HomeHeader(
                scope = scope,
                count = ui.visibleCount,
                grid = grid,
                hasTrash = (ui.counts[Scope.TRASH] ?: 0) > 0,
                onSearch = vm::openSearch,
                onToggleGrid = vm::toggleGrid,
                onEmptyTrash = onEmptyTrash,
            )
        }
        if (ui.tags.isNotEmpty()) {
            item(key = "tags", span = StaggeredGridItemSpan.FullLine) {
                TagFilterRow(ui, vm::selectTag)
            }
        }
        if (scope == Scope.TRASH && ui.visibleCount > 0) {
            item(key = "trash-banner", span = StaggeredGridItemSpan.FullLine) {
                Text(
                    stringResource(R.string.trash_banner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (ui.pinned.isNotEmpty()) {
            item(key = "section-pinned", span = StaggeredGridItemSpan.FullLine) {
                SectionLabel(stringResource(R.string.home_section_pinned))
            }
            items(ui.pinned, key = { it.id }) { note -> card(note)(Modifier.animateItem()) }
        }
        if (ui.others.isNotEmpty()) {
            if (ui.pinned.isNotEmpty()) {
                item(key = "section-others", span = StaggeredGridItemSpan.FullLine) {
                    SectionLabel(stringResource(R.string.home_section_others))
                }
            }
            items(ui.others, key = { it.id }) { note -> card(note)(Modifier.animateItem()) }
        }
        if (ui.loaded && ui.visibleCount == 0) {
            item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                val (kind, title, message) = when {
                    ui.tagFilter != null -> Triple(EmptyKind.TAG, R.string.empty_tag_title, R.string.empty_tag_message)
                    scope == Scope.ARCHIVE -> Triple(EmptyKind.ARCHIVE, R.string.empty_archive_title, R.string.empty_archive_message)
                    scope == Scope.TRASH -> Triple(EmptyKind.TRASH, R.string.empty_trash_title, R.string.empty_trash_message)
                    else -> Triple(EmptyKind.NOTES, R.string.empty_notes_title, R.string.empty_notes_message)
                }
                EmptyState(kind, stringResource(title), stringResource(message), Modifier.padding(top = 36.dp))
            }
        }
    }
}

@Composable
private fun HomeHeader(
    scope: Scope,
    count: Int,
    grid: Boolean,
    hasTrash: Boolean,
    onSearch: () -> Unit,
    onToggleGrid: () -> Unit,
    onEmptyTrash: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 0.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when (scope) {
                            Scope.NOTES -> R.string.home_title_notes
                            Scope.ARCHIVE -> R.string.home_title_archive
                            Scope.TRASH -> R.string.home_title_trash
                        },
                    ),
                    style = MaterialTheme.typography.displaySmall,
                    color = scheme.onBackground,
                )
                Text(
                    pluralStringResource(R.plurals.note_count, count, count),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (scope == Scope.TRASH) {
                if (hasTrash) {
                    TextButton(onClick = onEmptyTrash) {
                        Text(stringResource(R.string.trash_empty_action), color = scheme.error, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                }
            } else {
                IconButton(onClick = onToggleGrid) {
                    Icon(
                        if (grid) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                        contentDescription = stringResource(if (grid) R.string.home_view_list else R.string.home_view_grid),
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        if (scope != Scope.TRASH) {
            Spacer(Modifier.height(14.dp))
            Surface(
                onClick = onSearch,
                shape = RoundedCornerShape(26.dp),
                color = scheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(52.dp),
            ) {
                Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.home_search_hint), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TagFilterRow(ui: HomeUi, onSelect: (String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LazyRow(
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") { TagChip(stringResource(R.string.tag_all), ui.tagFilter == null) { onSelect(null) } }
        items(ui.tags, key = { it.name.lowercase() }) { tag ->
            TagChip("#${tag.name}  ${tag.count}", ui.tagFilter.equals(tag.name, ignoreCase = true)) { onSelect(tag.name) }
        }
    }
}

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = scheme.surfaceContainer,
            labelColor = scheme.onSurfaceVariant,
            selectedContainerColor = scheme.primary,
            selectedLabelColor = scheme.onPrimary,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = androidx.compose.ui.graphics.Color.Transparent,
            selectedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 0.dp),
    )
}

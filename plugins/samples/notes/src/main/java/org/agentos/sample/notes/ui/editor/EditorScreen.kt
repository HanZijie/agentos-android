package org.agentos.sample.notes.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.FormatStrikethrough
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import org.agentos.sample.notes.R
import org.agentos.sample.notes.data.NoteException
import org.agentos.sample.notes.data.NoteLimits
import org.agentos.sample.notes.data.NoteStatus
import org.agentos.sample.notes.data.NoteText
import org.agentos.sample.notes.data.TagCount
import org.agentos.sample.notes.markdown.EditResult
import org.agentos.sample.notes.markdown.LineKind
import org.agentos.sample.notes.markdown.MarkdownEdit
import org.agentos.sample.notes.ui.NotesViewModel
import org.agentos.sample.notes.ui.components.ColorPickerSheet
import org.agentos.sample.notes.ui.components.ConfirmDialog
import org.agentos.sample.notes.ui.components.MarkdownView
import org.agentos.sample.notes.ui.components.NotesSnackbar
import org.agentos.sample.notes.ui.components.TagPill
import org.agentos.sample.notes.ui.components.formatNoteTime
import org.agentos.sample.notes.ui.theme.containerColor

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: NotesViewModel, session: EditorSession, allTags: List<TagCount>, snackbar: SnackbarHostState) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state by session.state.collectAsState()

    var title by remember { mutableStateOf(TextFieldValue(state.title)) }
    var body by remember { mutableStateOf(TextFieldValue(state.content)) }
    var preview by rememberSaveable { mutableStateOf(false) }
    var showColors by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    val bodyFocus = remember { FocusRequester() }
    val titleFocus = remember { FocusRequester() }

    // 外部（MCP）改动被同步进来时，把输入框也刷新
    LaunchedEffect(state.title) { if (title.text != state.title) title = TextFieldValue(state.title) }
    LaunchedEffect(state.contentVersion) {
        if (body.text != state.content) {
            body = TextFieldValue(state.content, TextRange(minOf(body.selection.start, state.content.length)))
        }
    }
    LaunchedEffect(state.syncedCount) {
        if (state.syncedCount > 0) snackbar.showSnackbar(resources.getString(R.string.snack_synced))
    }
    // 新建的备忘录直接把光标放进正文
    LaunchedEffect(Unit) { if (state.noteId == null && state.content.isEmpty()) bodyFocus.requestFocus() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { scope.launch { session.flush() } }

    val readOnly = state.readOnly
    val editing = !preview && !readOnly
    val conflict = state.conflict
    val copySuffix = stringResource(R.string.conflict_copy_suffix)

    fun requestBack() {
        if (conflict != null) showConflictDialog = true else vm.back()
    }
    BackHandler(onBack = ::requestBack)

    fun applyEdit(result: EditResult) {
        body = TextFieldValue(result.text, TextRange(result.selStart, result.selEnd))
        session.setContent(result.text)
    }

    val pageColor by animateColorAsState(state.color.containerColor(), tween(250), label = "page")

    Box(Modifier.fillMaxSize().background(pageColor)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            EditorTopBar(
                state = state,
                preview = preview || readOnly,
                canTogglePreview = !readOnly,
                onBack = ::requestBack,
                onTogglePreview = { preview = !preview },
                onPin = { session.setPinned(!state.pinned) },
                onColor = { showColors = true },
                showMenu = showMenu,
                onShowMenu = { showMenu = it },
                onArchive = { vm.archiveFromEditor(state.status != NoteStatus.ARCHIVED) },
                onTrash = { vm.trashFromEditor() },
                onRestore = { scope.launch { session.restore() } },
                onDeleteForever = { confirmDelete = true },
            )

            ConflictBanners(
                state = state,
                onTakeTheirs = session::takeTheirs,
                onKeepMine = session::keepMine,
                onSaveCopy = {
                    scope.launch {
                        if (session.saveAsCopy(copySuffix) != null) snackbar.showSnackbar(resources.getString(R.string.snack_copy_saved))
                    }
                },
                onSaveNew = { scope.launch { session.saveAsNew() } },
                onRestore = { scope.launch { session.restore() } },
            )

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            ) {
                Spacer(Modifier.height(6.dp))
                // 标题
                if (editing) {
                    BasicTextField(
                        value = title,
                        onValueChange = {
                            title = it
                            session.setTitle(it.text.replace("\n", " "))
                        },
                        textStyle = MaterialTheme.typography.headlineMedium.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { bodyFocus.requestFocus() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                        decorationBox = { inner ->
                            Box {
                                if (title.text.isEmpty()) {
                                    Text(stringResource(R.string.editor_title_hint), style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface.copy(alpha = 0.3f))
                                }
                                inner()
                            }
                        },
                    )
                } else {
                    Text(
                        state.title.ifEmpty { stringResource(R.string.editor_untitled) },
                        style = MaterialTheme.typography.headlineMedium,
                        color = if (state.title.isEmpty()) scheme.onSurface.copy(alpha = 0.35f) else scheme.onSurface,
                    )
                }

                // 时间与字数
                val chars = remember(state.content) { state.content.count { !it.isWhitespace() } }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    state.updatedAt?.let {
                        Text(formatNoteTime(context, it), style = MaterialTheme.typography.labelMedium, color = scheme.onSurface.copy(alpha = 0.5f))
                        Text("  ·  ", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface.copy(alpha = 0.35f))
                    }
                    Text(pluralStringResource(R.plurals.editor_chars, chars, chars), style = MaterialTheme.typography.labelMedium, color = scheme.onSurface.copy(alpha = 0.5f))
                }

                // 标签
                FlowRow(
                    Modifier.padding(top = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    state.tags.forEach { tag ->
                        TagPill(
                            tag,
                            onRemove = if (readOnly) null else ({ session.setTags(state.tags - tag) }),
                            removeDescription = stringResource(R.string.editor_remove_tag, tag),
                            emphasized = true,
                        )
                    }
                    if (!readOnly) {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, scheme.onSurface.copy(alpha = 0.25f), RoundedCornerShape(50))
                                .clickable { showTagDialog = true }
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(13.dp), tint = scheme.onSurface.copy(alpha = 0.6f))
                            Spacer(Modifier.width(3.dp))
                            Text(stringResource(R.string.editor_add_tag), style = MaterialTheme.typography.labelSmall, color = scheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }

                // 正文：编辑 / 预览
                AnimatedContent(
                    targetState = editing,
                    transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                    label = "mode",
                ) { isEditing ->
                    if (isEditing) {
                        BasicTextField(
                            value = body,
                            onValueChange = { new ->
                                val handled = MarkdownEdit.onEnter(body.text, body.selection.start, body.selection.end, new.text, new.selection.start)
                                if (handled != null) {
                                    applyEdit(handled)
                                } else {
                                    body = new
                                    session.setContent(new.text)
                                }
                            },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                            cursorBrush = SolidColor(scheme.primary),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).focusRequester(bodyFocus),
                            decorationBox = { inner ->
                                Box {
                                    if (body.text.isEmpty()) {
                                        Text(stringResource(R.string.editor_body_hint), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface.copy(alpha = 0.3f))
                                    }
                                    inner()
                                }
                            },
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                            if (state.content.isBlank()) {
                                Text(stringResource(R.string.editor_empty_preview), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface.copy(alpha = 0.35f))
                            } else {
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    MarkdownView(state.content, onToggleTask = if (readOnly) null else session::toggleTask)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            AnimatedVisibility(visible = editing, enter = fadeIn(tween(120)) + expandVertically(), exit = fadeOut(tween(100)) + shrinkVertically()) {
                FormatToolbar { op -> applyEdit(op(body.text, body.selection.start, body.selection.end)) }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 64.dp)) { NotesSnackbar(it) }
    }

    if (showColors) {
        ColorPickerSheet(
            selected = state.color,
            onPick = {
                session.setColor(it)
                showColors = false
            },
            onDismiss = { showColors = false },
        )
    }
    if (showTagDialog) {
        AddTagDialog(
            existing = state.tags,
            suggestions = allTags.map { it.name }.filter { s -> state.tags.none { it.equals(s, ignoreCase = true) } },
            onAdd = { raw ->
                try {
                    session.setTags(NoteText.normalizeTags(state.tags + raw))
                    null
                } catch (e: NoteException) {
                    if (raw.length > NoteLimits.MAX_TAG_CHARS) R.string.tag_dialog_error_long else R.string.tag_dialog_error_count
                }
            },
            onDismiss = { showTagDialog = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_title),
            message = stringResource(R.string.dialog_delete_message, state.title.ifEmpty { stringResource(R.string.editor_untitled) }),
            confirmText = stringResource(R.string.dialog_delete_confirm),
            cancelText = stringResource(R.string.action_cancel),
            onConfirm = {
                confirmDelete = false
                vm.deleteForeverFromEditor()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (showConflictDialog) {
        AlertDialog(
            onDismissRequest = { showConflictDialog = false },
            shape = RoundedCornerShape(28.dp),
            containerColor = scheme.surface,
            title = { Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text(stringResource(R.string.conflict_message), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { showConflictDialog = false; session.takeTheirs(); vm.back() }) { Text(stringResource(R.string.conflict_take_theirs)) }
                    TextButton(onClick = {
                        showConflictDialog = false
                        scope.launch {
                            session.saveAsCopy(copySuffix)
                            vm.back()
                        }
                    }) { Text(stringResource(R.string.conflict_save_copy)) }
                    TextButton(onClick = {
                        showConflictDialog = false
                        scope.launch {
                            session.keepMine()
                            session.flush()
                            vm.back()
                        }
                    }) { Text(stringResource(R.string.conflict_keep_mine)) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showConflictDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun EditorTopBar(
    state: EditorSession.State,
    preview: Boolean,
    canTogglePreview: Boolean,
    onBack: () -> Unit,
    onTogglePreview: () -> Unit,
    onPin: () -> Unit,
    onColor: () -> Unit,
    showMenu: Boolean,
    onShowMenu: (Boolean) -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = scheme.onSurface)
        }
        Box(Modifier.weight(1f).padding(start = 4.dp)) { SaveIndicator(state) }
        if (canTogglePreview) {
            IconButton(onClick = onTogglePreview) {
                Icon(
                    if (preview) Icons.Rounded.Edit else Icons.Rounded.Visibility,
                    stringResource(if (preview) R.string.editor_edit else R.string.editor_preview),
                    tint = scheme.onSurface,
                )
            }
        }
        if (!state.readOnly) {
            IconButton(onClick = onPin) {
                Icon(
                    if (state.pinned) Icons.Rounded.PushPin else Icons.Outlined.PushPin,
                    stringResource(if (state.pinned) R.string.menu_unpin else R.string.menu_pin),
                    tint = if (state.pinned) scheme.primary else scheme.onSurface,
                )
            }
            IconButton(onClick = onColor) {
                Icon(Icons.Rounded.Palette, stringResource(R.string.menu_color), tint = scheme.onSurface)
            }
        }
        if (state.noteId != null) {
            Box {
                IconButton(onClick = { onShowMenu(true) }) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more), tint = scheme.onSurface)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { onShowMenu(false) },
                    shape = RoundedCornerShape(18.dp),
                    containerColor = scheme.surfaceContainerHigh,
                    tonalElevation = 0.dp,
                ) {
                    @Composable
                    fun item(label: Int, icon: ImageVector, destructive: Boolean = false, action: () -> Unit) {
                        val color = if (destructive) scheme.error else scheme.onSurface
                        DropdownMenuItem(
                            text = { Text(stringResource(label), color = color) },
                            leadingIcon = { Icon(icon, null, tint = color) },
                            onClick = { onShowMenu(false); action() },
                        )
                    }
                    when (state.status) {
                        NoteStatus.ACTIVE -> {
                            item(R.string.menu_archive, Icons.Rounded.Archive, action = onArchive)
                            item(R.string.menu_trash, Icons.Rounded.Delete, destructive = true, action = onTrash)
                        }
                        NoteStatus.ARCHIVED -> {
                            item(R.string.menu_unarchive, Icons.Rounded.Unarchive, action = onArchive)
                            item(R.string.menu_trash, Icons.Rounded.Delete, destructive = true, action = onTrash)
                        }
                        NoteStatus.TRASHED -> {
                            item(R.string.menu_restore, Icons.Rounded.Restore, action = onRestore)
                            item(R.string.menu_delete_forever, Icons.Rounded.DeleteForever, destructive = true, action = onDeleteForever)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveIndicator(state: EditorSession.State) {
    val scheme = MaterialTheme.colorScheme
    data class Label(val text: Int, val color: androidx.compose.ui.graphics.Color, val check: Boolean)
    val label = when {
        state.conflict != null -> Label(R.string.save_paused, scheme.error, false)
        state.save == EditorSession.SaveState.ERROR -> Label(R.string.save_error, scheme.error, false)
        state.save == EditorSession.SaveState.SAVING -> Label(R.string.save_saving, scheme.onSurface.copy(alpha = 0.55f), false)
        state.save == EditorSession.SaveState.DIRTY -> Label(R.string.save_dirty, scheme.onSurface.copy(alpha = 0.55f), false)
        state.noteId == null || state.readOnly -> null
        else -> Label(R.string.save_saved, scheme.primary, true)
    }
    AnimatedContent(label, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "save") { l ->
        if (l != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (l.check) {
                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(14.dp), tint = l.color)
                    Spacer(Modifier.width(4.dp))
                }
                Text(stringResource(l.text), style = MaterialTheme.typography.labelMedium, color = l.color)
            }
        } else {
            Spacer(Modifier.height(1.dp))
        }
    }
}

@Composable
private fun ConflictBanners(
    state: EditorSession.State,
    onTakeTheirs: () -> Unit,
    onKeepMine: () -> Unit,
    onSaveCopy: () -> Unit,
    onSaveNew: () -> Unit,
    onRestore: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val conflict = state.conflict
    val kind = when {
        conflict is EditorSession.Conflict.Updated -> 1
        conflict is EditorSession.Conflict.Deleted -> 2
        state.readOnly -> 3
        else -> 0
    }
    AnimatedVisibility(visible = kind != 0, enter = fadeIn(tween(180)) + expandVertically(), exit = fadeOut(tween(120)) + shrinkVertically()) {
        val (container, content) = when (kind) {
            2 -> scheme.errorContainer to scheme.onErrorContainer
            3 -> scheme.surfaceContainerHigh to scheme.onSurface
            else -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        }
        Surface(
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                when (kind) {
                    1 -> {
                        Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(stringResource(R.string.conflict_message), style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                            TextButton(onClick = onTakeTheirs) { Text(stringResource(R.string.conflict_take_theirs), color = content, fontWeight = FontWeight.SemiBold) }
                            TextButton(onClick = onSaveCopy) { Text(stringResource(R.string.conflict_save_copy), color = content, fontWeight = FontWeight.SemiBold) }
                            TextButton(onClick = onKeepMine) { Text(stringResource(R.string.conflict_keep_mine), color = content, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                    2 -> {
                        Text(stringResource(R.string.deleted_banner), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onSaveNew) { Text(stringResource(R.string.deleted_save_new), color = content, fontWeight = FontWeight.SemiBold) }
                    }
                    3 -> {
                        Text(
                            stringResource(if (state.save == EditorSession.SaveState.DIRTY) R.string.trashed_banner_while_editing else R.string.trashed_banner),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onRestore) { Text(stringResource(R.string.menu_restore), color = scheme.primary, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
    }
}

private typealias FormatOp = (text: String, start: Int, end: Int) -> EditResult

@Composable
private fun FormatToolbar(onApply: (FormatOp) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tools: List<Triple<Int, ImageVector, FormatOp>> = listOf(
        Triple(R.string.fmt_heading, Icons.Rounded.Title) { t, s, e -> MarkdownEdit.toggleLine(t, s, e, LineKind.HEADING) },
        Triple(R.string.fmt_bold, Icons.Rounded.FormatBold) { t, s, e -> MarkdownEdit.wrap(t, s, e, "**", "bold") },
        Triple(R.string.fmt_italic, Icons.Rounded.FormatItalic) { t, s, e -> MarkdownEdit.wrap(t, s, e, "*", "italic") },
        Triple(R.string.fmt_strike, Icons.Rounded.FormatStrikethrough) { t, s, e -> MarkdownEdit.wrap(t, s, e, "~~", "text") },
        Triple(R.string.fmt_bullet, Icons.AutoMirrored.Rounded.FormatListBulleted) { t, s, e -> MarkdownEdit.toggleLine(t, s, e, LineKind.BULLET) },
        Triple(R.string.fmt_numbered, Icons.Rounded.FormatListNumbered) { t, s, e -> MarkdownEdit.toggleLine(t, s, e, LineKind.NUMBERED) },
        Triple(R.string.fmt_task, Icons.Rounded.Checklist) { t, s, e -> MarkdownEdit.toggleLine(t, s, e, LineKind.TASK) },
        Triple(R.string.fmt_quote, Icons.Rounded.FormatQuote) { t, s, e -> MarkdownEdit.toggleLine(t, s, e, LineKind.QUOTE) },
        Triple(R.string.fmt_code, Icons.Rounded.Code) { t, s, e -> MarkdownEdit.wrap(t, s, e, "`", "code") },
        Triple(R.string.fmt_code_block, Icons.Rounded.DataObject) { t, s, e -> MarkdownEdit.codeBlock(t, s, e) },
        Triple(R.string.fmt_link, Icons.Rounded.Link) { t, s, e -> MarkdownEdit.link(t, s, e) },
        Triple(R.string.fmt_rule, Icons.Rounded.HorizontalRule) { t, s, e -> MarkdownEdit.rule(t, s, e) },
    )
    Surface(color = scheme.surfaceContainer.copy(alpha = 0.92f), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
            tools.forEach { (label, icon, op) ->
                IconButton(onClick = { onApply(op) }, modifier = Modifier.size(44.dp)) {
                    Icon(icon, stringResource(label), Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTagDialog(
    existing: List<String>,
    suggestions: List<String>,
    /** 返回 null 表示成功，否则是错误提示的字符串资源。 */
    onAdd: (String) -> Int?,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun submit(value: String) {
        val trimmed = value.trim().trimStart('#').trim()
        if (trimmed.isEmpty()) return
        val failure = onAdd(trimmed)
        if (failure == null) onDismiss() else error = failure
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = scheme.surface,
        title = { Text(stringResource(R.string.tag_dialog_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("#", style = MaterialTheme.typography.bodyLarge, color = scheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it; error = null },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                            cursorBrush = SolidColor(scheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submit(text) }),
                            modifier = Modifier.weight(1f).focusRequester(focus),
                            decorationBox = { inner ->
                                Box {
                                    if (text.isEmpty()) Text(stringResource(R.string.tag_dialog_hint), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                                    inner()
                                }
                            },
                        )
                    }
                }
                error?.let {
                    Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = scheme.error, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                }
                if (suggestions.isNotEmpty()) {
                    Text(
                        stringResource(R.string.tag_dialog_existing),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp, start = 4.dp),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggestions.take(12).forEach { s -> TagPill(s, onClick = { submit(s) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.action_add), fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = scheme.onSurfaceVariant) } },
    )
}

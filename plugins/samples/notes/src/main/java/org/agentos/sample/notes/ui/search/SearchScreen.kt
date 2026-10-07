package org.agentos.sample.notes.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.agentos.sample.notes.R
import org.agentos.sample.notes.data.MatchField
import org.agentos.sample.notes.data.NoteSearch
import org.agentos.sample.notes.data.SearchHit
import org.agentos.sample.notes.ui.NotesViewModel
import org.agentos.sample.notes.ui.SearchUi
import org.agentos.sample.notes.ui.components.EmptyKind
import org.agentos.sample.notes.ui.components.EmptyState
import org.agentos.sample.notes.ui.components.TagPill
import org.agentos.sample.notes.ui.components.cardBorderColor
import org.agentos.sample.notes.ui.components.formatNoteTime
import org.agentos.sample.notes.ui.components.highlightStyle
import org.agentos.sample.notes.ui.components.highlighted
import org.agentos.sample.notes.ui.theme.accentColor
import org.agentos.sample.notes.ui.theme.containerColor

@Composable
fun SearchScreen(vm: NotesViewModel, ui: SearchUi) {
    val scheme = MaterialTheme.colorScheme
    var field by remember { mutableStateOf(TextFieldValue(vm.query.value)) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().imePadding().navigationBarsPadding()) {
        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = scheme.onSurface)
            }
            Surface(shape = RoundedCornerShape(26.dp), color = scheme.surfaceContainer, modifier = Modifier.weight(1f).height(52.dp)) {
                Row(Modifier.padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(21.dp), tint = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = field,
                        onValueChange = {
                            field = it
                            vm.setQuery(it.text)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.weight(1f).focusRequester(focus),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (field.text.isEmpty()) {
                                    Text(stringResource(R.string.search_field_hint), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                                }
                                inner()
                            }
                        },
                    )
                    if (field.text.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                field = TextFieldValue("")
                                vm.setQuery("")
                            },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.action_clear), Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        if (ui.tags.isNotEmpty()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.tags, key = { it.name.lowercase() }) { tag ->
                    val selected = ui.tag.equals(tag.name, ignoreCase = true)
                    FilterChip(
                        selected = selected,
                        onClick = { vm.selectSearchTag(if (selected) null else tag.name) },
                        label = { Text("#${tag.name}", style = MaterialTheme.typography.labelLarge) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = scheme.surfaceContainer,
                            labelColor = scheme.onSurfaceVariant,
                            selectedContainerColor = scheme.primary,
                            selectedLabelColor = scheme.onPrimary,
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = Color.Transparent, selectedBorderColor = Color.Transparent),
                    )
                }
            }
        }

        when {
            ui.query.isBlank() -> EmptyState(
                EmptyKind.SEARCH,
                stringResource(R.string.search_prompt_title),
                stringResource(R.string.search_prompt_message),
                Modifier.padding(top = 32.dp),
            )
            ui.hits.isEmpty() -> EmptyState(
                EmptyKind.SEARCH,
                stringResource(R.string.empty_search_title),
                stringResource(R.string.empty_search_message),
                Modifier.padding(top = 32.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "count") {
                    Text(
                        pluralStringResource(R.plurals.search_result_count, ui.total, ui.total),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
                    )
                }
                items(ui.hits, key = { it.note.id }) { hit ->
                    SearchResultCard(hit, ui.query, Modifier.animateItem()) { vm.openEditor(hit.note.id) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchResultCard(hit: SearchHit, query: String, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val note = hit.note
    val shape = RoundedCornerShape(20.dp)
    val mark = highlightStyle()
    val terms = remember(query) { NoteSearch.terms(query) }
    val title = note.displayTitle.ifEmpty { stringResource(R.string.editor_untitled) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(note.color.containerColor())
            .border(1.dp, cardBorderColor(note.color), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                highlighted(title, hit.titleRanges, mark),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (note.pinned) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.PushPin, null, Modifier.size(15.dp), tint = if (note.color == org.agentos.sample.notes.data.NoteColor.DEFAULT) scheme.primary else note.color.accentColor())
            }
        }
        if (hit.snippet.text.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            Text(
                highlighted(hit.snippet.text, hit.snippet.ranges, mark),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface.copy(alpha = 0.75f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(9.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            note.tags.take(4).forEach { tag ->
                TagPill(tag, emphasized = MatchField.TAG in hit.matchedIn && terms.any { tag.contains(it, ignoreCase = true) })
            }
            if (note.isArchived) Text(stringResource(R.string.archived_mark), style = MaterialTheme.typography.labelSmall, color = scheme.onSurface.copy(alpha = 0.55f))
            Text(formatNoteTime(context, note.updatedAt), style = MaterialTheme.typography.labelSmall, color = scheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

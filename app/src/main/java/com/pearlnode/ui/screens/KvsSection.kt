package com.pearlnode.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pearlnode.R
import com.pearlnode.model.KvsEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** How many entries the card shows before it has to be expanded. */
private const val COLLAPSED_ENTRY_COUNT = 3

/**
 * Contents of the device's key-value store.
 *
 * What lands in there is written by whatever scripts run on the device, so the
 * card makes no assumptions about the shape of a value: JSON is offered for
 * expansion, everything else is shown as plain text. The card hides itself
 * entirely on devices whose store is empty.
 */
@Composable
fun KvsSection(
    entries: List<KvsEntry>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    if (entries.isEmpty() && error == null) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val hasMore = entries.size > COLLAPSED_ENTRY_COUNT
    val visible = if (expanded || !hasMore) entries else entries.take(COLLAPSED_ENTRY_COUNT)

    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.kvs_title), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    if (hasMore) {
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = stringResource(
                                    if (expanded) R.string.kvs_show_fewer else R.string.kvs_show_all
                                ),
                            )
                        }
                    }
                }
            }

            error?.let { message ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
                }
            }

            visible.forEach { entry ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                KvsRow(entry)
            }

            if (hasMore && !expanded) {
                Text(
                    stringResource(R.string.kvs_more, entries.size - COLLAPSED_ENTRY_COUNT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun KvsRow(entry: KvsEntry) {
    var expanded by rememberSaveable(entry.key) { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(entry.key, style = MaterialTheme.typography.titleSmall)
            if (expanded && entry.isStructured) {
                Text(
                    formatKvsValue(entry.value),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    entry.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.isStructured) {
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.kvs_collapse_value else R.string.kvs_expand_value
                    ),
                )
            }
        }
    }
}

private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

/**
 * Lays a JSON value out over several lines.
 *
 * A JSON object loses its outer braces and contributes one line per member;
 * members that are themselves objects or arrays are pretty-printed below their
 * key. Anything else -- a top-level array, or text that is not JSON at all --
 * is returned pretty-printed as a whole, or unchanged if it does not parse.
 */
internal fun formatKvsValue(raw: String): String {
    val element = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return raw
    if (element !is JsonObject || element.isEmpty()) return prettyOrRaw(element, raw)
    return element.entries.joinToString("\n") { (key, value) -> "$key:${renderMember(value)}" }
}

private fun renderMember(value: JsonElement): String = when (value) {
    is JsonPrimitive -> " " + (value.contentOrNull ?: value.toString())
    else -> "\n" + prettyJson.encodeToString(JsonElement.serializer(), value).prependIndent("  ")
}

private fun prettyOrRaw(element: JsonElement, raw: String): String =
    runCatching { prettyJson.encodeToString(JsonElement.serializer(), element) }.getOrDefault(raw)

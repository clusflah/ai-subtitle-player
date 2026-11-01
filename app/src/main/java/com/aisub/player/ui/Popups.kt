package com.aisub.player.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * 弹出层（底部面板、菜单、对话框）统一用一种底色：跟随亮色、暗色主题，轻微半透明，
 * 透出一点后面的画面。
 */
@Composable
fun popupColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = popupColor(),
    content: @Composable ColumnScope.() -> Unit,
) = ModalBottomSheet(
    onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState,
    containerColor = containerColor, tonalElevation = 0.dp, content = content,
)

@Composable
fun AppMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    containerColor: Color = popupColor(),
    content: @Composable ColumnScope.() -> Unit,
) = DropdownMenu(
    expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier, offset = offset,
    scrollState = scrollState, shape = MaterialTheme.shapes.medium, containerColor = containerColor,
    tonalElevation = 0.dp, content = content,
)

@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) = androidx.compose.material3.AlertDialog(
    onDismissRequest = onDismissRequest, confirmButton = confirmButton, modifier = modifier,
    dismissButton = dismissButton, icon = icon, title = title, text = text,
    containerColor = popupColor(), tonalElevation = 0.dp, properties = properties,
)

package com.aisub.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// App 里所有输入框都用这里的 OutlinedTextField / TextField（和 material3 的同名、参数一样，同一个包里优先用这个）。
// 手机上和 material3 的完全一样。电视上：
// · 遥控器：焦点停在输入框外层，移过去不弹输入法、方向键照常移动；按确认键才进去编辑、弹输入法，收起输入法就退出来
// · 右边多一个手机按钮：扫码在手机上打字，提交后填回这个输入框
// 已经有整张表单「用手机输入」的地方传 phoneInput = false，不再每个框都放一个。

@Composable
fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    phoneInput: Boolean = true,
    phoneLabel: String = "",
) = TvInput(
    value, onValueChange, modifier, enabled && !readOnly, phoneInput, phoneLabel,
    secret = visualTransformation is PasswordVisualTransformation, keyboardOptions,
) { m, kb ->
    androidx.compose.material3.OutlinedTextField(
        value, onValueChange, m, enabled, readOnly, textStyle, label, placeholder, leadingIcon, trailingIcon,
        supportingText = supportingText, isError = isError, visualTransformation = visualTransformation,
        keyboardOptions = kb, keyboardActions = keyboardActions, singleLine = singleLine,
        maxLines = maxLines, minLines = minLines, shape = shape, colors = colors,
    )
}

@Composable
fun OutlinedTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
    phoneInput: Boolean = true,
    phoneLabel: String = "",
) = TvInput(
    value.text, { onValueChange(TextFieldValue(it, TextRange(it.length))) }, modifier, enabled && !readOnly, phoneInput, phoneLabel,
    secret = visualTransformation is PasswordVisualTransformation, keyboardOptions,
) { m, kb ->
    androidx.compose.material3.OutlinedTextField(
        value, onValueChange, m, enabled, readOnly, textStyle, label, placeholder, leadingIcon, trailingIcon,
        supportingText = supportingText, isError = isError, visualTransformation = visualTransformation,
        keyboardOptions = kb, keyboardActions = keyboardActions, singleLine = singleLine,
        maxLines = maxLines, minLines = minLines, shape = shape, colors = colors,
    )
}

@Composable
fun TextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    textStyle: TextStyle = LocalTextStyle.current,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    colors: TextFieldColors = TextFieldDefaults.colors(),
    phoneInput: Boolean = true,
    phoneLabel: String = "",
) = TvInput(value, onValueChange, modifier, enabled, phoneInput, phoneLabel, secret = false, keyboardOptions) { m, kb ->
    androidx.compose.material3.TextField(
        value, onValueChange, m, enabled, textStyle = textStyle, placeholder = placeholder, singleLine = singleLine,
        keyboardOptions = kb, keyboardActions = keyboardActions, colors = colors,
    )
}

/** 单行紧凑搜索框：固定高度、占位符单行截断不换行，保留电视遥控器焦点框与手机扫码输入支持。 */
@Composable
fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    phoneInput: Boolean = true,
    phoneLabel: String = "",
    height: Dp = 44.dp,
) = TvInput(
    value = value,
    onValueChange = onValueChange,
    modifier = modifier,
    editable = true,
    phoneInput = phoneInput,
    phoneLabel = phoneLabel,
    secret = false,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
) { m, kb ->
    var isFocused by remember { mutableStateOf(false) }
    val glass = LocalLiquidGlassEnabled.current
    val shape = RoundedCornerShape(12.dp)
    val containerColor = if (glass) {
        if (isFocused) currentVisualPalette().cardTint else currentVisualPalette().fieldContainerTint
    } else {
        if (isFocused) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow
    }
    val borderColor = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isFocused) 1.5.dp else 1.dp
    Row(
        modifier = m
            .height(height)
            .clip(shape)
            .let {
                if (glass) {
                    it.liquidGlass(shape = shape, style = GlassStyle.Pill, tint = containerColor, enableBackdrop = false)
                } else {
                    it.background(containerColor, shape)
                }
            }
            .border(borderWidth, borderColor, shape)
            .padding(start = 12.dp, end = if (value.isNotEmpty()) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = kb,
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { isFocused = it.isFocused },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    AppIcons.Close,
                    contentDescription = "清空",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 电视上给输入框加遥控器处理和手机输入按钮；手机上原样画出来 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    editable: Boolean,
    phoneInput: Boolean,
    phoneLabel: String,
    secret: Boolean,
    keyboardOptions: KeyboardOptions,
    field: @Composable (Modifier, KeyboardOptions) -> Unit,
) {
    if (!LocalTv.current) return field(modifier, keyboardOptions)
    if (!editable) return field(modifier, keyboardOptions)
    // 输入框本身平时不能获得焦点（有的电视一获得焦点就弹输入法，左右键也只会挪光标）：
    // 焦点停在外面这一层，按确认键才进去编辑、弹输入法；输入法收起后退回外层，方向键照常移动
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var editing by remember { mutableStateOf(false) }
    var imeShown by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val outerFocus = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        imeShown = false
        fieldFocus.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible && editing) imeShown = true
        else if (!imeVisible && editing && imeShown) {
            editing = false
            outerFocus.requestFocus()
        }
    }
    val inner: @Composable (Modifier) -> Unit = { m ->
        Box(
            m.focusRequester(outerFocus)
                .tvFocusRing(RoundedCornerShape(6.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) { editing = true },
        ) {
            field(
                Modifier.fillMaxWidth().focusRequester(fieldFocus)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged { if (!it.isFocused && editing && imeShown) editing = false },
                keyboardOptions,
            )
        }
    }
    if (!phoneInput) return inner(modifier)
    var phone by remember { mutableStateOf(false) }
    // 调用方的 modifier（宽度、weight、focusRequester）给外面这一行，输入框占满剩下的地方
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        inner(Modifier.weight(1f))
        IconButton(onClick = { phone = true }) { Icon(AppIcons.Phone, "用手机输入") }
    }
    if (phone) {
        val label = phoneLabel.ifBlank { "内容" }
        AppDialog(
            onDismissRequest = { phone = false },
            title = { Text("用手机输入") },
            // 屏幕矮时二维码下面的地址和取消按钮会超出：可以滚动
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PhoneFormPanel(
                        title = phoneLabel.ifBlank { "在手机上输入" },
                        caption = "用手机扫码（手机和电视要在同一个局域网），在手机上填写后提交，会自动填到电视上。",
                        fields = listOf(PhoneField("v", label, if (secret) "" else value, secret = secret)),
                        onCancel = { phone = false },
                        onValues = { v -> phone = false; v["v"]?.let(onValueChange) },
                    )
                }
            },
            confirmButton = {},
        )
    }
}

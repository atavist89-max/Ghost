package com.ghost.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ghost.app.BuildConfig
import com.ghost.app.ui.theme.GhostColors
import com.ghost.app.ui.theme.VT323

// Phosphor Green Pip-Boy Colors
private val PhosphorGreen = Color(0xFF39FF14)
private val PhosphorDim = Color(0xFF2B8C1A)
private val PhosphorBright = Color(0xFF5FFF3F)
private val GunmetalBg = Color(0xFF0A0F0A)
private val GunmetalSurface = Color(0xFF141414)
private val MetallicDark = Color(0xFF2A2A2A)
private val MetallicLight = Color(0xFF4A4A4A)
private val BoltColor = Color(0xFF6A6A6A)

/**
 * Pip-Boy Terminal UI Component - Industrial 1950s-60s CRT Terminal Aesthetic
 *
 * Features:
 * - Compact 260dp×380dp wrist-mounted display
 * - Iris mechanical eye mascot (40×24dp scaled)
 * - Industrial frame with bolt heads and metallic border
 * - Heavy CRT scanlines and phosphor bloom effects
 * - VT323 terminal font throughout
 * - Physical-looking tabs: [VISUAL] [DATA] [STAT]
 * - Holotape thumbnail with notched corners
 * - Terminal response area with line numbers
 * - Flat command line input with > prompt
 */
@Composable
fun GhostInterface(
    capturedBitmap: Bitmap?,
    responseText: String,
    isGenerating: Boolean,
    isEngineReady: Boolean = false,
    isKeyboardOpen: Boolean = false,
    onSendQuery: (String) -> Unit,
    onClose: () -> Unit,
    onDebugClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Auto-collapse when keyboard opens
    LaunchedEffect(isKeyboardOpen) {
        if (isKeyboardOpen && isExpanded) isExpanded = false
    }

    // Iris state management
    var irisState by remember { mutableStateOf(IrisView.State.IDLE) }
    var cursorPosition by remember { mutableFloatStateOf(0.5f) }
    var lastTypingTime by remember { mutableLongStateOf(0L) }
    var isTyping by remember { mutableStateOf(false) }

    // Track typing and cursor position
    LaunchedEffect(query) {
        if (query.isNotEmpty()) {
            isTyping = true
            lastTypingTime = System.currentTimeMillis()
            irisState = IrisView.State.LISTENING
            cursorPosition = if (query.isEmpty()) 0.5f else 0.3f + (query.length.coerceAtMost(20) / 20f) * 0.4f
        } else {
            isTyping = false
        }
    }

    // Auto-transition from LISTENING to FOCUSED after pause
    LaunchedEffect(lastTypingTime) {
        if (isTyping && irisState == IrisView.State.LISTENING) {
            kotlinx.coroutines.delay(1000)
            if (System.currentTimeMillis() - lastTypingTime >= 1000) {
                irisState = IrisView.State.FOCUSED
            }
        }
    }

    // Update iris state based on generation status
    LaunchedEffect(isGenerating, responseText) {
        irisState = when {
            isGenerating && responseText.isEmpty() -> IrisView.State.THINKING
            isGenerating && responseText.isNotEmpty() -> IrisView.State.ANALYZING
            !isGenerating && responseText.isNotEmpty() -> IrisView.State.SUCCESS
            else -> if (isTyping) irisState else IrisView.State.IDLE
        }
    }

    // Auto-scroll to bottom when new text arrives
    LaunchedEffect(responseText) {
        if (responseText.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    // Blinking cursor animation for terminal
    val cursorAlpha by animateFloatAsState(
        targetValue = if (isGenerating) 0f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(530, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(2.dp)
    ) {
        // Industrial container with CRT effects
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(2.dp))
                .background(GunmetalBg.copy(alpha = 0.95f))
                .border(
                    width = 4.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(MetallicDark, MetallicLight, MetallicDark),
                        start = Offset(0f, 0f),
                        end = Offset(0f, Float.MAX_VALUE)
                    ),
                    shape = RoundedCornerShape(2.dp)
                )
                .drawBehind { drawInnerBevel() }
                .drawWithContent {
                    drawContent()
                    drawHeavyScanlines()
                    drawVignette()
                }
                .padding(6.dp)
        ) {
            // Header: Iris + Tabs + Holotape
            PipBoyHeader(
                irisState = irisState,
                cursorPosition = cursorPosition,
                screenshot = capturedBitmap,
                isThumbnailExpanded = isExpanded,
                onThumbnailClick = { isExpanded = !isExpanded },
                onClose = onClose,
                onDebugClick = onDebugClick
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Phosphor divider
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(PhosphorGreen.copy(alpha = 0.5f))
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Terminal response area with line numbers
            TerminalResponseArea(
                responseText = responseText,
                isGenerating = isGenerating,
                isEngineReady = isEngineReady,
                scrollState = scrollState,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Terminal command line input
            TerminalInputLine(
                query = query,
                onQueryChange = {
                    query = it
                    cursorPosition = if (it.isEmpty()) 0.5f else 0.3f + (it.length.coerceAtMost(20) / 20f) * 0.4f
                },
                onSend = {
                    if (query.isNotBlank()) {
                        onSendQuery(query)
                        query = ""
                        irisState = IrisView.State.THINKING
                    }
                },
                enabled = !isGenerating && isEngineReady,
                cursorAlpha = cursorAlpha
            )
        }

        // Corner bolts overlay
        CornerBolts()
    }
}

@Composable
private fun PipBoyHeader(
    irisState: IrisView.State,
    cursorPosition: Float,
    screenshot: Bitmap?,
    isThumbnailExpanded: Boolean,
    onThumbnailClick: () -> Unit,
    onClose: () -> Unit,
    onDebugClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Iris mechanical eye (40×24dp scaled)
        AndroidView(
            factory = { context ->
                IrisView(context).apply {
                    setState(irisState)
                    setCursorPosition(cursorPosition)
                }
            },
            update = { irisView ->
                irisView.setState(irisState)
                irisView.setCursorPosition(cursorPosition)
            },
            modifier = Modifier.size(40.dp, 24.dp)
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Tabs: [VISUAL] [DATA] [STAT]
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val tabs = listOf("VISUAL", "DATA", "STAT")
            tabs.forEachIndexed { index, label ->
                val isActive = index == 0 // VISUAL active by default
                Text(
                    text = "[$label]",
                    fontFamily = VT323,
                    fontSize = 10.sp,
                    color = if (isActive) PhosphorBright else PhosphorDim.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Holotape thumbnail (60×60, notched corners)
        if (screenshot != null) {
            HolotapeThumbnail(
                bitmap = screenshot,
                isExpanded = isThumbnailExpanded,
                onClick = onThumbnailClick
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Close button (compact)
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = PhosphorGreen,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun HolotapeThumbnail(
    bitmap: Bitmap,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val size = if (isExpanded) 100.dp else 50.dp

    Box(
        modifier = Modifier
            .size(size)
            .background(GunmetalSurface)
            .drawBehind {
                // Notched corners effect (draw dark triangles at corners)
                val notchSize = 8.dp.toPx()
                // Top-left notch
                drawRect(
                    color = GunmetalBg,
                    topLeft = Offset(0f, 0f),
                    size = Size(notchSize, notchSize)
                )
                // Bottom-right notch
                drawRect(
                    color = GunmetalBg,
                    topLeft = Offset(size.width - notchSize, size.height - notchSize),
                    size = Size(notchSize, notchSize)
                )
            }
            .clickable(onClick = onClick)
            .padding(2.dp)
    ) {
        Column {
            // DATA label
            Text(
                text = "DATA",
                fontFamily = VT323,
                fontSize = 7.sp,
                color = PhosphorDim.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            // Image with monochrome green tint
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Screen data",
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.8f)
                    .drawWithContent {
                        drawContent()
                        // Green monochrome overlay
                        drawRect(
                            color = PhosphorGreen.copy(alpha = 0.2f),
                            blendMode = androidx.compose.ui.graphics.BlendMode.SrcAtop
                        )
                    }
            )
        }
    }
}

@Composable
private fun TerminalResponseArea(
    responseText: String,
    isGenerating: Boolean,
    isEngineReady: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth()) {
        // Line numbers column
        Column(
            modifier = Modifier
                .width(20.dp)
                .fillMaxHeight()
                .background(GunmetalSurface.copy(alpha = 0.3f))
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Generate line numbers based on content
            val lineCount = responseText.count { it == '\n' } + 1
            repeat((lineCount + 2).coerceAtMost(20)) { index ->
                Text(
                    text = String.format("%02d", index + 1),
                    fontFamily = VT323,
                    fontSize = 10.sp,
                    color = PhosphorDim.copy(alpha = 0.5f)
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(GunmetalSurface.copy(alpha = 0.2f))
                .padding(6.dp)
        ) {
            if (!isEngineReady && responseText.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = PhosphorGreen,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "INITIALIZING...",
                        fontFamily = VT323,
                        fontSize = 10.sp,
                        color = PhosphorDim
                    )
                }
            } else if (responseText.isEmpty() && !isGenerating) {
                Text(
                    text = "> READY",
                    fontFamily = VT323,
                    fontSize = 12.sp,
                    color = PhosphorDim.copy(alpha = 0.6f)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = responseText,
                        fontFamily = VT323,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        color = PhosphorGreen,
                        letterSpacing = 0.05.sp
                    )

                    // Blinking block cursor
                    if (isGenerating) {
                        Text(
                            text = "▊",
                            fontFamily = VT323,
                            fontSize = 12.sp,
                            color = PhosphorGreen
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalInputLine(
    query: String,
    onQueryChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    cursorAlpha: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(Color.Transparent)
            .drawBehind {
                // Top border only
                drawRect(
                    color = PhosphorGreen.copy(alpha = 0.5f),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, 1.dp.toPx())
                )
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Prompt
        Text(
            text = ">",
            fontFamily = VT323,
            fontSize = 14.sp,
            color = PhosphorGreen
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Input field
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                enabled = enabled,
                textStyle = TextStyle(
                    color = PhosphorGreen,
                    fontSize = 12.sp,
                    fontFamily = VT323
                ),
                decorationBox = { innerTextField ->
                    Box {
                        innerTextField()
                        // Blinking cursor when focused and empty
                        if (query.isEmpty() && enabled) {
                            Text(
                                text = "_",
                                fontFamily = VT323,
                                fontSize = 12.sp,
                                color = PhosphorGreen.copy(alpha = cursorAlpha),
                                modifier = Modifier.alpha(cursorAlpha)
                            )
                        }
                    }
                },
                singleLine = true
            )
        }

        // Execute button
        IconButton(
            onClick = onSend,
            enabled = enabled && query.isNotBlank(),
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Execute",
                tint = if (enabled && query.isNotBlank()) PhosphorGreen else PhosphorDim.copy(alpha = 0.3f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun CornerBolts() {
    val boltSize = 4.dp
    Box(modifier = Modifier.fillMaxSize()) {
        // Top-left
        Box(
            modifier = Modifier
                .size(boltSize)
                .align(Alignment.TopStart)
                .background(BoltColor, RoundedCornerShape(2.dp))
        )
        // Top-right
        Box(
            modifier = Modifier
                .size(boltSize)
                .align(Alignment.TopEnd)
                .background(BoltColor, RoundedCornerShape(2.dp))
        )
        // Bottom-left
        Box(
            modifier = Modifier
                .size(boltSize)
                .align(Alignment.BottomStart)
                .background(BoltColor, RoundedCornerShape(2.dp))
        )
        // Bottom-right
        Box(
            modifier = Modifier
                .size(boltSize)
                .align(Alignment.BottomEnd)
                .background(BoltColor, RoundedCornerShape(2.dp))
        )
    }
}

// DrawScope extensions for CRT effects

private fun DrawScope.drawInnerBevel() {
    // Inner shadow to simulate recessed CRT
    drawRect(
        color = Color.Black.copy(alpha = 0.3f),
        topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
        size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx())
    )
}

private fun DrawScope.drawHeavyScanlines() {
    // Heavy CRT scanlines: 2px on, 2px off, 40% opacity
    val lineHeight = 2.dp.toPx()
    val spacing = 4.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(
            color = Color.Black.copy(alpha = 0.4f),
            topLeft = Offset(0f, y),
            size = Size(size.width, lineHeight)
        )
        y += spacing
    }
}

private fun DrawScope.drawVignette() {
    // Corner darkening for CRT effect
    val gradient = Brush.radialGradient(
        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.4f)),
        center = center,
        radius = size.width * 0.7f
    )
    drawRect(brush = gradient)
}

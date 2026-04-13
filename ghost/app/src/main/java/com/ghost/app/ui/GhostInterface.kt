package com.ghost.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ghost.app.BuildConfig
import com.ghost.app.ui.theme.GhostColors
import com.ghost.app.ui.theme.VT323

// Phosphor Green Cyberpunk Colors
private val PhosphorGreen = Color(0xFF39FF14)
private val PhosphorDim = Color(0xFF2B8C1A)
private val PhosphorBright = Color(0xFF5FFF3F)
private val GunmetalBg = Color(0xFF0A0F0A)
private val GunmetalSurface = Color(0xFF141414)
private val GunmetalSurfaceVariant = Color(0xFF1A1A1A)
private val MetallicGray = Color(0xFF4A4A4A)
private val DarkMetal = Color(0xFF2A2A2A)
private val TextPhosphor = Color(0xFFE0FFE0)
private val TextPhosphorDim = Color(0xFF8FBC8F)
private val BorderPhosphor = Color(0xFF39FF14)

/**
 * Ghost PiP UI Component - Pip-Boy Industrial Terminal Aesthetic
 * 
 * Features:
 * - Compact 260x380dp wrist-mounted terminal
 * - Iris mechanical eye mascot (40x24dp, sole brand identifier)
 * - Industrial housing with bolt heads and metallic border
 * - Heavy CRT scanlines (40% opacity)
 * - VT323 terminal font throughout
 * - Holotape data thumbnail with notched corners
 * - Terminal command line input (> prompt)
 * - Line numbers in response area
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
            cursorPosition = 0.3f + (query.length.coerceAtMost(20) / 20f) * 0.4f
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(2.dp)  // Reduced from 4dp
    ) {
        // Industrial container with bolts and bevel
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(2.dp))  // Almost square industrial corners
                .background(GunmetalBg.copy(alpha = 0.95f))
                .drawBehind { 
                    drawInnerBevel()
                    drawCRTBloom()
                }
                .drawWithContent {
                    drawContent()
                    drawScanlines()
                    drawVignette()
                }
                .padding(6.dp)  // Reduced from 12dp
        ) {
            // Header: Iris + Tabs + Holotape thumbnail
            PipBoyHeader(
                irisState = irisState,
                cursorPosition = cursorPosition,
                screenshot = capturedBitmap,
                isThumbnailExpanded = isExpanded,
                onThumbnailClick = { isExpanded = !isExpanded },
                onClose = onClose,
                onDebugClick = onDebugClick
            )

            Spacer(modifier = Modifier.height(4.dp))  // Reduced from 8dp

            // Phosphor divider line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BorderPhosphor.copy(alpha = 0.5f))
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

            Spacer(modifier = Modifier.height(6.dp))  // Reduced from 12dp

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
                enabled = !isGenerating && isEngineReady
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
            .height(36.dp),  // Reduced from 48dp
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Iris mechanical eye (40x24dp)
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

        // Tabs: [ VISUAL ] [ DATA ] [ STAT ]
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("VISUAL", "DATA", "STAT").forEachIndexed { index, label ->
                val isActive = index == 0  // VISUAL active by default
                Text(
                    text = "[$label]",
                    fontFamily = VT323,
                    fontSize = 20.sp,
                    color = if (isActive) PhosphorBright else PhosphorDim.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Holotape thumbnail (60x60, notched corners)
        if (screenshot != null) {
            HolotapeThumbnail(
                bitmap = screenshot,
                isExpanded = isThumbnailExpanded,
                onClick = onThumbnailClick
            )
            
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Debug button (only in debug builds)
        if (BuildConfig.DEBUG) {
            IconButton(
                onClick = onDebugClick,
                modifier = Modifier.size(28.dp)
            ) {
                Text(
                    text = "⚙",
                    fontFamily = VT323,
                    fontSize = 28.sp,
                    color = TextPhosphorDim
                )
            }
        }

        // Close X
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
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // DATA label
        Text(
            text = "DATA",
            fontFamily = VT323,
            fontSize = 16.sp,
            color = PhosphorDim.copy(alpha = 0.7f)
        )
        
        // Notched corner thumbnail
        Box(
            modifier = Modifier
                .size(size)
                .clip(HolotapeShape())
                .background(GunmetalSurfaceVariant)
                .border(
                    width = 1.dp,
                    color = BorderPhosphor.copy(alpha = 0.5f),
                    shape = HolotapeShape()
                )
                .clickable(onClick = onClick)
                .padding(2.dp)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Data tape",
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.8f)  // Monochrome green filter effect
            )
            
            // Green overlay for holotape effect
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PhosphorGreen.copy(alpha = 0.1f))
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GunmetalSurface.copy(alpha = 0.4f))
            .padding(4.dp)
    ) {
        // Line numbers column
        Column(
            modifier = Modifier.width(20.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (responseText.isNotEmpty()) {
                val lines = responseText.split("\n").size.coerceAtLeast(1)
                repeat(lines.coerceAtMost(99)) { index ->
                    Text(
                        text = String.format("%02d", index + 1),
                        fontFamily = VT323,
                        fontSize = 22.sp,
                        color = PhosphorDim.copy(alpha = 0.5f),
                        lineHeight = 28.sp
                    )
                }
            } else {
                Text(
                    text = "01",
                    fontFamily = VT323,
                    fontSize = 22.sp,
                    color = PhosphorDim.copy(alpha = 0.5f)
                )
            }
        }
        
        Spacer(modifier = Modifier.width(4.dp))
        
        // Content area
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(4.dp)
        ) {
            if (!isEngineReady && responseText.isEmpty()) {
                // Loading state
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = PhosphorGreen,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "INITIALIZING...",
                        fontFamily = VT323,
                        fontSize = 20.sp,
                        color = PhosphorDim
                    )
                }
            } else if (responseText.isEmpty() && !isGenerating) {
                // Empty state
                Text(
                    text = "> STANDBY",
                    fontFamily = VT323,
                    fontSize = 24.sp,
                    color = TextPhosphorDim.copy(alpha = 0.4f)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    // Response text
                    Text(
                        text = responseText,
                        fontFamily = VT323,
                        fontSize = 24.sp,
                        color = TextPhosphor,
                        lineHeight = 28.sp,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Blinking block cursor when generating
                    if (isGenerating) {
                        Spacer(modifier = Modifier.height(2.dp))
                        BlinkingCursor()
                    }
                }
            }
        }
    }
}

@Composable
private fun BlinkingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorBlink"
    )
    
    Text(
        text = "█",
        fontFamily = VT323,
        fontSize = 24.sp,
        color = PhosphorGreen.copy(alpha = alpha)
    )
}

@Composable
private fun TerminalInputLine(
    query: String,
    onQueryChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)  // Reduced from 44dp
            .drawBehind {
                // Top border only (1dp phosphor line)
                drawRect(
                    color = BorderPhosphor.copy(alpha = 0.5f),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, 1.dp.toPx())
                )
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Prompt
        Text(
            text = ">",
            fontFamily = VT323,
            fontSize = 28.sp,
            color = PhosphorGreen,
            modifier = Modifier.padding(end = 4.dp)
        )
        
        // Input field
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = enabled,
            textStyle = TextStyle(
                color = TextPhosphor,
                fontSize = 26.sp,
                fontFamily = VT323,
                letterSpacing = 0.1.sp
            ),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (query.isEmpty()) {
                        Text(
                            text = "_",
                            fontFamily = VT323,
                            fontSize = 26.sp,
                            color = TextPhosphorDim.copy(alpha = 0.3f)
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            singleLine = true
        )
        
        // Blinking cursor when typing
        if (query.isNotEmpty() && enabled) {
            BlinkingCursor()
            Spacer(modifier = Modifier.width(4.dp))
        }
        
        // Execute button
        IconButton(
            onClick = onSend,
            enabled = enabled && query.isNotBlank(),
            modifier = Modifier.size(28.dp)
        ) {
            Text(
                text = "⏎",
                fontFamily = VT323,
                fontSize = 28.sp,
                color = if (enabled && query.isNotBlank()) PhosphorGreen else TextPhosphorDim.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun CornerBolts() {
    val boltColor = Color(0xFF6A6A6A)
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Top-left bolt
        Box(
            modifier = Modifier
                .size(4.dp)
                .offset(x = 2.dp, y = 2.dp)
                .background(boltColor, CircleShape)
                .align(Alignment.TopStart)
        )
        
        // Top-right bolt
        Box(
            modifier = Modifier
                .size(4.dp)
                .offset(x = (-2).dp, y = 2.dp)
                .background(boltColor, CircleShape)
                .align(Alignment.TopEnd)
        )
        
        // Bottom-left bolt
        Box(
            modifier = Modifier
                .size(4.dp)
                .offset(x = 2.dp, y = (-2).dp)
                .background(boltColor, CircleShape)
                .align(Alignment.BottomStart)
        )
        
        // Bottom-right bolt
        Box(
            modifier = Modifier
                .size(4.dp)
                .offset(x = (-2).dp, y = (-2).dp)
                .background(boltColor, CircleShape)
                .align(Alignment.BottomEnd)
        )
    }
}

// Holotape shape with notched corners
private class HolotapeShape : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density
    ): androidx.compose.ui.graphics.Outline {
        val notchSize = 8f
        val path = androidx.compose.ui.graphics.Path().apply {
            // Start after top-left notch
            moveTo(notchSize, 0f)
            // Top edge to top-right
            lineTo(size.width, 0f)
            // Right edge
            lineTo(size.width, size.height - notchSize)
            // Bottom-right notch
            lineTo(size.width - notchSize, size.height)
            // Bottom edge to bottom-left
            lineTo(0f, size.height)
            // Left edge to top-left notch
            lineTo(0f, notchSize)
            // Close to start
            close()
        }
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

/**
 * Draw inner bevel to simulate recessed CRT screen
 */
private fun DrawScope.drawInnerBevel() {
    // Inner shadow
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(Color.Black.copy(alpha = 0.0f), Color.Black.copy(alpha = 0.3f)),
            center = center,
            radius = size.width * 0.7f
        ),
        size = size
    )
}

/**
 * Draw heavy CRT scanlines (40% opacity)
 */
private fun DrawScope.drawScanlines() {
    val lineHeight = 2.dp.toPx()
    val spacing = 4.dp.toPx()  // 2px on, 2px off
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

/**
 * Draw phosphor bloom effect around edges
 */
private fun DrawScope.drawCRTBloom() {
    // Subtle phosphor glow at edges
    val strokeWidth = 2.dp.toPx()
    
    // Top glow
    drawRect(
        color = BorderPhosphor.copy(alpha = 0.15f),
        topLeft = Offset(0f, 0f),
        size = Size(size.width, strokeWidth * 2)
    )
    
    // Bottom glow
    drawRect(
        color = BorderPhosphor.copy(alpha = 0.1f),
        topLeft = Offset(0f, size.height - strokeWidth * 2),
        size = Size(size.width, strokeWidth * 2)
    )
}

/**
 * Draw vignette effect (darkening at corners)
 */
private fun DrawScope.drawVignette() {
    val gradient = Brush.radialGradient(
        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
        center = center,
        radius = size.width * 0.75f
    )
    drawRect(brush = gradient)
}

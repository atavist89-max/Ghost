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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghost.app.BuildConfig
import com.ghost.app.ui.theme.GhostColors
import com.ghost.app.ui.theme.XantiTypewriter

// Phosphor Green Cyberpunk Colors
private val PhosphorGreen = Color(0xFF39FF14)
private val PhosphorDim = Color(0xFF2B8C1A)
private val PhosphorBright = Color(0xFF5FFF3F)
private val GunmetalBg = Color(0xFF0A0F0A)
private val GunmetalSurface = Color(0xFF141414)
private val GunmetalSurfaceVariant = Color(0xFF1A1A1A)
private val TextPhosphor = Color(0xFFE0FFE0)
private val TextPhosphorDim = Color(0xFF8FBC8F)
private val BorderPhosphor = Color(0xFF39FF14)

/**
 * Ghost PiP UI Component - Cyberpunk Terminal Aesthetic
 * 
 * Features:
 * - Phosphor green text on gunmetal background
 * - CRT scanline overlay effect
 * - Spring physics slide-in from right
 * - Expandable screenshot thumbnail
 * - Xanti Typewriter font for AI responses
 */
@Composable
fun GhostInterface(
    capturedBitmap: Bitmap?,
    responseText: String,
    isGenerating: Boolean,
    isEngineReady: Boolean = false,
    onSendQuery: (String) -> Unit,
    onClose: () -> Unit,
    onDebugClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom when new text arrives
    LaunchedEffect(responseText) {
        if (responseText.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    
    // Pulsing animation for processing state
    val pulseAnimation by animateFloatAsState(
        targetValue = if (isGenerating) 1.0f else 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(4.dp)
    ) {
        // Main container with CRT effect
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(6.dp))
                .background(GunmetalBg.copy(alpha = 0.95f))
                .drawBehind { drawPhosphorGlow() }
                .drawWithContent {
                    drawContent()
                    drawScanlines()
                }
                .padding(12.dp)
        ) {
            // Header bar with phosphor border
            GhostHeader(
                onClose = onClose,
                onDebugClick = onDebugClick,
                isGenerating = isGenerating,
                pulseAlpha = if (isGenerating) pulseAnimation else 0.7f
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Phosphor divider line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BorderPhosphor.copy(alpha = 0.5f))
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            // Screenshot thumbnail
            capturedBitmap?.let { bitmap ->
                GhostThumbnail(
                    bitmap = bitmap,
                    isExpanded = isExpanded,
                    onClick = { isExpanded = !isExpanded }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            // Response area with scrollable text (takes available space)
            GhostResponseArea(
                responseText = responseText,
                isGenerating = isGenerating,
                isEngineReady = isEngineReady,
                scrollState = scrollState,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Input area at bottom - stays visible above keyboard
            GhostInputArea(
                query = query,
                onQueryChange = { query = it },
                onSend = {
                    if (query.isNotBlank()) {
                        onSendQuery(query)
                        query = ""
                    }
                },
                enabled = !isGenerating && isEngineReady
            )
        }
    }
}

@Composable
private fun GhostHeader(
    onClose: () -> Unit,
    onDebugClick: () -> Unit,
    isGenerating: Boolean,
    pulseAlpha: Float
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Title with phosphor glow
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Status indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isGenerating) PhosphorGreen.copy(alpha = pulseAlpha) else PhosphorDim
                    )
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Text(
                text = "GHOST",
                color = PhosphorGreen,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            
            if (isGenerating) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "PROCESSING...",
                    color = PhosphorDim,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Debug button (only in debug builds)
            if (BuildConfig.DEBUG) {
                IconButton(
                    onClick = onDebugClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Debug",
                        tint = TextPhosphorDim,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            // Close button
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = PhosphorGreen,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun GhostThumbnail(
    bitmap: Bitmap,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val targetHeight = if (isExpanded) 180.dp else 70.dp
    val borderAlpha = if (isExpanded) 1.0f else 0.3f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp, max = targetHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(GunmetalSurfaceVariant)
            .drawBehind {
                // Phosphor border glow
                drawRect(
                    color = BorderPhosphor.copy(alpha = borderAlpha),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, 1.dp.toPx())
                )
            }
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured screen",
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
        )

        // Expand/collapse indicator
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(4.dp))
                .background(GunmetalBg.copy(alpha = 0.8f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = if (isExpanded) "[TAP TO COLLAPSE]" else "[TAP TO EXPAND]",
                color = TextPhosphorDim,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
private fun GhostResponseArea(
    responseText: String,
    isGenerating: Boolean,
    isEngineReady: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(GunmetalSurface.copy(alpha = 0.6f))
            .padding(12.dp)
    ) {
        if (!isEngineReady && responseText.isEmpty()) {
            // Loading state
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = PhosphorGreen,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "INITIALIZING NEURAL NET...",
                    color = PhosphorDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        } else if (responseText.isEmpty() && !isGenerating) {
            // Empty state placeholder - uses Xanti Typewriter font
            Text(
                text = "> AWAITING INPUT...",
                color = TextPhosphorDim.copy(alpha = 0.4f),
                fontSize = 14.sp,
                fontFamily = XantiTypewriter,  // Xanti Typewriter font
                letterSpacing = 0.15.sp,
                modifier = Modifier.align(Alignment.TopStart)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                // Response text with Xanti Typewriter font
                Text(
                    text = responseText,
                    color = TextPhosphor,
                    fontSize = 14.sp,
                    fontFamily = XantiTypewriter,  // Xanti Typewriter font for AI responses
                    lineHeight = 20.sp,
                    letterSpacing = 0.15.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                // Blinking cursor when generating - uses Xanti Typewriter font
                if (isGenerating) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "█",
                        color = PhosphorGreen.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontFamily = XantiTypewriter  // Xanti Typewriter font
                    )
                }
            }
        }
    }
}

@Composable
private fun GhostInputArea(
    query: String,
    onQueryChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(GunmetalSurfaceVariant)
            .drawBehind {
                // Subtle phosphor outline
                drawRect(
                    color = BorderPhosphor.copy(alpha = 0.3f),
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, size.height),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                )
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = enabled,
            textStyle = TextStyle(
                color = TextPhosphor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.25.sp
            ),
            decorationBox = { innerTextField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (query.isEmpty()) {
                        Text(
                            text = "> HOW CAN I HELP?",
                            color = TextPhosphorDim.copy(alpha = 0.5f),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            letterSpacing = 0.25.sp
                        )
                    }
                    innerTextField()
                }
            },
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
            singleLine = true
        )

        IconButton(
            onClick = onSend,
            enabled = enabled && query.isNotBlank(),
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Send,
                contentDescription = "Send",
                tint = if (enabled && query.isNotBlank()) PhosphorGreen else TextPhosphorDim.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Draw scanline effect for CRT terminal aesthetic
 */
private fun DrawScope.drawScanlines() {
    val scanlineSpacing = 4.dp.toPx()
    val scanlineAlpha = 0.02f
    
    var y = 0f
    while (y < size.height) {
        drawRect(
            color = Color.Black.copy(alpha = scanlineAlpha),
            topLeft = Offset(0f, y),
            size = Size(size.width, 1.dp.toPx())
        )
        y += scanlineSpacing
    }
}

/**
 * Draw phosphor glow effect around edges
 */
private fun DrawScope.drawPhosphorGlow() {
    val strokeWidth = 2.dp.toPx()
    
    // Top border glow
    drawRect(
        color = BorderPhosphor.copy(alpha = 0.8f),
        topLeft = Offset(0f, 0f),
        size = Size(size.width, strokeWidth)
    )
    
    // Left border glow
    drawRect(
        color = BorderPhosphor.copy(alpha = 0.3f),
        topLeft = Offset(0f, 0f),
        size = Size(strokeWidth, size.height)
    )
    
    // Right border glow
    drawRect(
        color = BorderPhosphor.copy(alpha = 0.3f),
        topLeft = Offset(size.width - strokeWidth, 0f),
        size = Size(strokeWidth, size.height)
    )
    
    // Bottom border glow
    drawRect(
        color = BorderPhosphor.copy(alpha = 0.3f),
        topLeft = Offset(0f, size.height - strokeWidth),
        size = Size(size.width, strokeWidth)
    )
}

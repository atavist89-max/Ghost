package com.ghost.app.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ghost.app.R

// Phosphor Green colors
private val PhosphorGreen = Color(0xFF39FF14)
private val PhosphorGreenDim = Color(0xFF1A800A)
private val DarkBackground = Color(0xFF0A0A0A)
private val DarkSurface = Color(0xFF141414)
private val DarkSurfaceVariant = Color(0xFF1E1E1E)
private val TextPrimary = Color.White
private val TextSecondary = Color(0xFFB3B3B3)

/**
 * Ghost PiP UI Component.
 * Provides the floating window interface for interacting with the LLM.
 */
@Composable
fun GhostInterface(
    capturedBitmap: Bitmap?,
    responseText: String,
    isGenerating: Boolean,
    onSendQuery: (String) -> Unit,
    onClose: () -> Unit,
    onBitmapClick: () -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var isExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // Auto-scroll to bottom when new text arrives
    LaunchedEffect(responseText) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground.copy(alpha = 0.95f))
            .padding(8.dp)
    ) {
        // Header with close button
        GhostHeader(onClose = onClose)

        // Screenshot thumbnail
        capturedBitmap?.let { bitmap ->
            GhostThumbnail(
                bitmap = bitmap,
                isExpanded = isExpanded,
                onClick = { isExpanded = !isExpanded }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Response area
        GhostResponseArea(
            responseText = responseText,
            isGenerating = isGenerating,
            scrollState = scrollState
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Input area
        GhostInputArea(
            query = query,
            onQueryChange = { query = it },
            onSend = {
                if (query.isNotBlank()) {
                    onSendQuery(query)
                    query = ""
                }
            },
            enabled = !isGenerating
        )
    }
}

@Composable
private fun GhostHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "GHOST",
            color = PhosphorGreen,
            fontSize = 16.sp,
            letterSpacing = 2.sp
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun GhostThumbnail(
    bitmap: Bitmap,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val targetHeight = if (isExpanded) 200.dp else 80.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp, max = targetHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVariant)
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

        // Expand indicator
        if (!isExpanded) {
            Text(
                text = "Tap to expand",
                color = TextSecondary.copy(alpha = 0.7f),
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(DarkBackground.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun GhostResponseArea(
    responseText: String,
    isGenerating: Boolean,
    scrollState: androidx.compose.foundation.ScrollState
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface)
            .padding(12.dp)
    ) {
        if (responseText.isEmpty() && !isGenerating) {
            Text(
                text = "Ask a question about the screen...",
                color = TextSecondary.copy(alpha = 0.5f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = responseText,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isGenerating) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "▌",
                        color = PhosphorGreen,
                        fontSize = 14.sp
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
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(DarkSurfaceVariant)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            enabled = enabled,
            textStyle = TextStyle(
                color = TextPrimary,
                fontSize = 14.sp
            ),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = "Ask about the screen...",
                            color = TextSecondary.copy(alpha = 0.5f),
                            fontSize = 14.sp
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
                tint = if (enabled && query.isNotBlank()) PhosphorGreen else TextSecondary.copy(alpha = 0.3f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Expanded image dialog for viewing full screenshot.
 */
@Composable
fun ExpandedImageDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground.copy(alpha = 0.9f))
                .clickable(onClick = onDismiss)
                .padding(16.dp)
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Full screenshot",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                    .clip(RoundedCornerShape(8.dp))
                    .align(Alignment.Center)
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = TextPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

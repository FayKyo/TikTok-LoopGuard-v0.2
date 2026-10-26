package com.loopguard.tiktok

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Calm "Detox Lockout" screen shown after TikTok is fully closed. */
class LockoutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LoopGuardTheme { LockoutScreen(onDone = { finish() }) } }
    }
}

@Composable
fun LockoutScreen(onDone: () -> Unit) {
    var left by remember { mutableIntStateOf(30) }
    LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- } }

    val t = rememberInfiniteTransition(label = "breath")
    val s by t.animateFloat(
        0.6f, 1f,
        infiniteRepeatable(tween(4000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Time's up for TikTok", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("Breathe in… and out.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(48.dp))
            Box(
                Modifier.size(180.dp).scale(s)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), CircleShape)
            )
            Spacer(Modifier.height(48.dp))
            if (left > 0) Text("$left s", style = MaterialTheme.typography.titleLarge)
            else Button(onClick = onDone) { Text("Done") }
        }
    }
}

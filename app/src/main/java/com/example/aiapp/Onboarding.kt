package com.example.aiapp

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private data class Step(val icon: ImageVector, val title: String, val body: String)

private val steps = listOf(
    Step(Icons.Filled.PhoneAndroid, "Welcome", "Your phone will run AI models and share them with other users through our server. Let's get it set up."),
    Step(Icons.Filled.BatteryChargingFull, "1. Keep it charged", "Keep your phone plugged in. Running a model uses a lot of battery."),
    Step(Icons.Filled.AcUnit, "2. Keep it cool", "Place your phone in a cool spot, between 10°C and 20°C. Heat slows the model down."),
    Step(Icons.Filled.Wifi, "3. Stay on Wi-Fi", "Keep your phone connected to Wi-Fi at all times so requests can reach it."),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    val last = index == steps.lastIndex

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top row: Back (leading), Skip (trailing).
        Box(Modifier.fillMaxWidth()) {
            if (index > 0) TextButton(
                onClick = { forward = false; index-- },
                modifier = Modifier.align(Alignment.CenterStart),
            ) { Text("Back", style = MaterialTheme.typography.titleMedium) }
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text("Skip", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        AnimatedContent(
            targetState = index,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(350)) { it * dir } + fadeIn(tween(350))) togetherWith
                    (slideOutHorizontally(tween(350)) { -it * dir } + fadeOut(tween(350)))
            },
            label = "onboarding",
        ) { i ->
            val step = steps[i]
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.size(160.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(step.icon, contentDescription = null, modifier = Modifier.size(84.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(32.dp))
                Text(step.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(
                    step.body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                )
            }
        }

        // iOS-style page indicator: wide capsule on the current dot.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(steps.size) { i ->
                val width by animateDpAsState(if (i == index) 20.dp else 8.dp, label = "dot")
                Box(
                    Modifier.height(8.dp).width(width)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (i == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        // iOS-style full-width primary button.
        Button(
            onClick = { if (last) onDone() else { forward = true; index++ } },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(if (last) "Get Started" else "Continue", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

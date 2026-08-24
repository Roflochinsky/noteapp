package com.roflochinsky.noteapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Онбординг-чеклист по компу: четыре шага + батарея, иллюстрация, «Продолжить». */
@Composable
fun OnboardingScreen(steps: List<OnboardStep>, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(26.dp, 20.dp)
            .navigationBarsPadding()
    ) {
        Text("Одна кнопка — и мысль уже в GitHub", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Несколько шагов, и телефон пишет по долгому нажатию питания.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )
        Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp)) {
            EmptyIllustration()
        }
        steps.forEachIndexed { i, step -> StepCard(i + 1, step) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DocPalette.Nav),
            enabled = steps.all { it.done },
        ) {
            Text(if (steps.all { it.done }) "Продолжить" else "Заверши шаги выше")
        }
    }
}

data class OnboardStep(
    val title: String,
    val subtitle: String,
    val done: Boolean,
    val action: () -> Unit,
)

@Composable
private fun StepCard(n: Int, step: OnboardStep) {
    Surface(
        color = androidx.compose.ui.graphics.Color.White,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DocPalette.Line),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable(onClick = step.action),
    ) {
        Row(
            modifier = Modifier.padding(16.dp, 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.size(32.dp)
                        .background(
                            if (step.done) DocPalette.Green.copy(alpha = 0.15f)
                            else DocPalette.Paper2,
                            CircleShape,
                        ),
                contentAlignment = Alignment.Center,
            ) {
                if (step.done) {
                    Icon(
                        Icons.Filled.Check,
                        null,
                        tint = DocPalette.Green,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text("$n", style = MaterialTheme.typography.titleSmall)
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(step.title, style = MaterialTheme.typography.titleSmall)
                Text(step.subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (!step.done) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = DocPalette.Blue)
            }
        }
    }
}

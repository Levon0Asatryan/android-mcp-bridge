package dev.healthbridge.ui

import android.content.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.healthbridge.HealthBridgeApplication
import dev.healthbridge.health.*
import java.util.UUID

@Composable
fun MealComposer(app: HealthBridgeApplication, busy: Boolean, run: (suspend () -> Unit) -> Unit, sharedText: String? = null) {
    val context = LocalContext.current
    var source by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<NutritionEntry?>(null) }
    var key by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sharedText) { if (sharedText != null) { source = sharedText; draft = null; result = null } }
    Panel("Add a meal from ChatGPT") {
        Text("Manual fallback: paste a nutrition estimate, review it, then save to Health Connect.")
        QuietInput(source, { if (it.length <= 32768) source = it }, "Paste nutrition JSON",
            minLines = 3, maxLines = 6, singleLine = false, enabled = !busy && draft == null)
        Row {
            TextButton(onClick = {
                source = context.getSystemService(ClipboardManager::class.java).primaryClip
                    ?.getItemAt(0)?.coerceToText(context)?.toString()?.take(32768).orEmpty()
            }, enabled = !busy && draft == null) { Text("Paste") }
            TextButton(onClick = {
                val prompt = """Estimate my meal's nutrition. Return only JSON with meal_name, meal_type (breakfast/lunch/dinner/snack/unknown), calories_kcal, protein_g, carbs_g, fat_g, optional sugar_g, fiber_g, sodium_mg. Use timestamp "${
                    deviceTimestamp()
                }" only if this is when I ate. Meal: [describe food and amounts]. Values are estimates."""
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Meal prompt", prompt))
            }) { Text("Copy prompt") }
        }
        PrimaryAction("Review meal", enabled = !busy && source.isNotBlank() && draft == null) {
            run { draft = importMeal(source); key = "healthbridge-phone:${UUID.randomUUID()}"; result = null }
        }
        draft?.let { entry ->
            Text(entry.meal_name, style = MaterialTheme.typography.titleMedium)
            Text("${entry.meal_type}  ${entry.timestamp}")
            Text("${entry.calories_kcal} kcal\nProtein ${entry.protein_g} g\nCarbs ${entry.carbs_g} g\nFat ${entry.fat_g} g")
            Text("Sugar ${entry.sugar_g ?: "Not provided"} g; fiber ${entry.fiber_g ?: "Not provided"} g; sodium ${entry.sodium_mg ?: "Not provided"} mg")
            Text("AI estimates may be inaccurate. Check these values before saving.")
            result?.let { Text(it) }
            if (result == null) PrimaryAction("Save to Health Connect", enabled = !busy) { confirm = true }
            SecondaryAction("New meal", enabled = !busy) { draft = null; result = null; source = "" }
        }
    }
    if (confirm) Confirmation("Save this meal?", "This writes the reviewed values to Health Connect. Samsung Health visibility is checked separately.",
        { confirm = false }, {
            confirm = false
            val entry = requireNotNull(draft)
            run {
                val written = app.health.addNutrition(entry, key)
                result = "Saved to Health Connect.\nRecord: ${written.record_id}\nSamsung Health visibility: not verified."
                app.audit.record(null, "phone_add_nutrition", "Success")
            }
        })
}

package com.example.boilerefficiencypro

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.abs
import com.hummeling.if97.IF97

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BoilerEfficiencyPro { report ->
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Boiler efficiency report")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(send, "Export/share report"))
        } }
    }
}

private data class HResult(val h: Double, val note: String)

// Engineering units in this library: pressure in bar absolute, temperature in °C,
// and specific enthalpy in kJ/kg. The engine implements IAPWS-IF97.
private val if97Engine = IF97(IF97.UnitSystem.ENGINEERING)

private fun steamH(pAbsBar: Double, tempC: Double): HResult? {
    return try {
        if (pAbsBar <= 0.0 || !pAbsBar.isFinite() || !tempC.isFinite()) return null
        val tsat = if97Engine.saturationTemperatureP(pAbsBar)
        // P/T cannot determine steam quality on the saturation line. For a main-steam
        // outlet this helper therefore accepts only clearly superheated steam.
        if (tempC <= tsat + 0.5) return null
        val h = if97Engine.specificEnthalpyPT(pAbsBar, tempC)
        if (!h.isFinite() || h <= 0.0) null
        else HResult(h, "Steam enthalpy calculated with the IAPWS-IF97 Java engine from absolute pressure and temperature.")
    } catch (_: Exception) {
        null // Includes IF97 out-of-range and invalid-state conditions.
    }
}

private fun feedwaterH(pAbsBar: Double, tempC: Double): HResult? {
    return try {
        if (pAbsBar <= 0.0 || !pAbsBar.isFinite() || !tempC.isFinite()) return null
        val h = if97Engine.specificEnthalpyPT(pAbsBar, tempC)
        if (!h.isFinite() || h < 0.0) null
        else HResult(h, "Feedwater enthalpy calculated with the IAPWS-IF97 Java engine from absolute pressure and temperature.")
    } catch (_: Exception) {
        null
    }
}
private data class DirectResult(val efficiency: Double, val usefulKw: Double, val fuelKw: Double, val massDiff: Double, val qPerKg: Double)
private data class IndirectResult(val efficiency: Double, val totalLoss: Double)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoilerEfficiencyPro(onShare: (String) -> Unit) {
    val f = remember { mutableStateMapOf<String, String>().apply {
        put("steamFlow",""); put("steamP",""); put("steamT",""); put("steamH","")
        put("feedFlow",""); put("feedP",""); put("feedT",""); put("feedH","")
        put("fuelFlow",""); put("fuelCV",""); put("blowFlow","0"); put("blowH","")
        put("sprayFlow","0"); put("sprayH",""); put("lossStack",""); put("lossMoisture","")
        put("lossUnburned",""); put("lossRadiation",""); put("lossOther",""); put("notes","")
        put("testId", ""); put("boilerId", ""); put("site", ""); put("operator", ""); put("ambientPressure", "1.01325")
    } }
    var pressureMode by remember { mutableStateOf("Gauge (barg)") }
    var fuelRateUnit by remember { mutableStateOf("kg/h") }
    var cvUnit by remember { mutableStateOf("kJ/kg") }
    var basis by remember { mutableStateOf("GCV / HHV") }
    var autoSteam by remember { mutableStateOf(false) }
    var autoFeed by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    var direct by remember { mutableStateOf<DirectResult?>(null) }
    var indirect by remember { mutableStateOf<IndirectResult?>(null) }
    var error by remember { mutableStateOf("") }
    var warnings by remember { mutableStateOf(listOf<String>()) }
    val num: (String) -> Double? = { f[it]?.trim()?.replace(",", "")?.toDoubleOrNull() }
    fun calculate() {
        error = ""; warnings = emptyList(); direct = null; indirect = null
        val ms = num("steamFlow"); val mf = num("feedFlow"); val fuel = num("fuelFlow"); val cv = num("fuelCV")
        val p = num("steamP"); val t = num("steamT"); val tf = num("feedT"); val pf = num("feedP")
        val blow = num("blowFlow") ?: 0.0; val spray = num("sprayFlow") ?: 0.0
        if (ms == null || mf == null || fuel == null || cv == null || ms <= 0 || mf <= 0 || fuel <= 0 || cv <= 0) {
            error = "Enter positive values for steam flow, feedwater flow, fuel rate and fuel calorific value."
            return
        }
        val hSteamResult = if (autoSteam) {
            if (p == null || t == null) null else {
                val patm = num("ambientPressure") ?: 1.01325
                if (patm <= 0.0) null else steamH(p + if (pressureMode.startsWith("Gauge")) patm else 0.0, t)
            }
        } else num("steamH")?.let { HResult(it, "User-entered steam enthalpy.") }
        val hFeedResult = if (autoFeed) {
            if (pf != null && tf != null && pf > 0.0) feedwaterH(pf, tf) else null
        } else num("feedH")?.let { HResult(it, "User-entered feedwater enthalpy.") }
        if (hSteamResult == null) {
            error = if (autoSteam) "IAPWS-IF97 could not calculate a valid superheated-steam state. Check pressure/temperature and IF97 limits. Saturated or wet steam needs verified enthalpy/quality data; otherwise switch to manual enthalpy." else "Enter steam enthalpy."
            return
        }
        if (hFeedResult == null) {
            error = if (autoFeed) "Enter feedwater absolute pressure (bar) and temperature (°C) within the IAPWS-IF97 range, or switch to manual enthalpy." else "Enter feedwater enthalpy."
            return
        }
        val hs = hSteamResult.h; val hf = hFeedResult.h
        val hblow = if (blow > 0) num("blowH") else 0.0
        val hspray = if (spray > 0) num("sprayH") else 0.0
        if (blow < 0 || spray < 0 || (blow > 0 && hblow == null) || (spray > 0 && hspray == null)) {
            error = "Blowdown/spray flow cannot be negative. Enter the matching enthalpy when its flow is above zero."
            return
        }
        val cvKJ = when(cvUnit) { "MJ/kg", "MJ/L" -> cv * 1000.0; else -> cv }
        if ((fuelRateUnit == "kg/h" && cvUnit.endsWith("/L")) || (fuelRateUnit == "L/h" && cvUnit.endsWith("/kg"))) {
            error = "Fuel rate and calorific value units do not match. Use kg/h with kJ/kg or L/h with kJ/L."
            return
        }
        val fuelInput = fuel * cvKJ // kJ/h
        // Balance around the chosen boundary: water/steam outputs minus feedwater and spray inputs.
        val useful = ms * hs + blow * (hblow ?: 0.0) - mf * hf - spray * (hspray ?: 0.0)
        if (useful <= 0.0) { error = "Useful heat is zero or negative. Recheck boundary, streams, units and enthalpies."; return }
        val eff = useful / fuelInput * 100.0
        if (!eff.isFinite() || eff <= 0.0 || eff > 100.0) warnings = warnings + "Efficiency is outside the expected 0–100% range. Verify instruments, steam properties, fuel CV basis and system boundary; do not accept this result without investigation."
        if (hs <= 0.0 || hf < 0.0) warnings = warnings + "One or more enthalpy values are nonphysical for this worksheet. Confirm units and reference basis."
        if (basis == "NCV / LHV") warnings = warnings + "NCV/LHV basis selected. Compare only with results calculated on the same calorific-value basis."
        val massDiff = mf + spray - ms - blow
        direct = DirectResult(eff, useful / 3600.0, fuelInput / 3600.0, massDiff, useful / ms)
        val lossNames = listOf("lossStack","lossMoisture","lossUnburned","lossRadiation","lossOther")
        val provided = lossNames.map { num(it) }
        if (provided.any { it != null }) {
            if (provided.any { it == null || it < 0 || it > 100 }) {
                warnings = warnings + "Indirect method: enter all five loss percentages, each between 0 and 100, or leave all blank."
            } else {
                val total = provided.filterNotNull().sum()
                indirect = if (total <= 100.0) IndirectResult(100.0 - total, total) else null
                if (total > 100.0) warnings = warnings + "Entered heat losses exceed 100%; check the values and avoid double-counting."
            }
        }
        warnings = warnings + hSteamResult.note + hFeedResult.note
        if (abs(massDiff) > maxOf(50.0, mf * 0.02)) warnings = warnings + "Mass balance difference is >2% (or >50 kg/h). Check synchronized meters and omitted streams."
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF075E66), secondary = Color(0xFFB85C38), background = Color(0xFFF4F7F6), surface = Color.White)) {
        Scaffold(topBar = {
            TopAppBar(title = { Column {
                Text("Boiler Efficiency Pro", fontWeight = FontWeight.Bold)
                Text("Engineering worksheet · v4.0", style = MaterialTheme.typography.labelSmall)
            } }, actions = {
                IconButton(onClick = {
                    f.keys.toList().forEach { key -> f[key] = when (key) { "blowFlow", "sprayFlow" -> "0"; "ambientPressure" -> "1.01325"; else -> "" } }
                    direct = null; indirect = null; error = ""; warnings = emptyList()
                }) { Icon(Icons.Default.RestartAlt, contentDescription = "Reset") }
            })
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE2F0ED))) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Engineering workflow: verified properties first", fontWeight = FontWeight.Bold)
                        Text("Manual enthalpy entry is the default. Optional automatic values use the IAPWS-IF97 industrial formulation; record the property method and keep all readings time-aligned. Verify reference cases before formal acceptance testing.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Direct method") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Heat-loss method") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Results & checks") })
                }
                when (tab) {
                    0 -> {
                        Header("Test identification")
                        InputField("Test / report ID", f, "testId", "text")
                        InputField("Boiler / unit ID", f, "boilerId", "text")
                        InputField("Plant / site", f, "site", "text")
                        InputField("Operator / engineer", f, "operator", "text")

                        Header("1 · Main steam outlet")
                        InputField("Main steam mass flow", f, "steamFlow", "kg/h")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Calculate steam enthalpy using IAPWS-IF97", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = autoSteam, onCheckedChange = { autoSteam = it })
                        }
                        if (autoSteam) {
                            InputField("Steam pressure", f, "steamP", "bar")
                            ChoiceRow("Pressure reading", pressureMode, listOf("Gauge (barg)", "Absolute (bara)")) { pressureMode = it }
                            InputField("Steam temperature", f, "steamT", "°C")
                            InputField("Local atmospheric pressure", f, "ambientPressure", "bar abs")
                            Text("Uses the IAPWS-IF97 industrial formulation. Requires a valid superheated state; pressure and temperature alone cannot determine wet-steam quality. Verify library outputs against reference cases before formal acceptance testing.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9A4B19))
                        } else InputField("Steam enthalpy from verified table", f, "steamH", "kJ/kg")

                        Header("2 · Feedwater inlet")
                        InputField("Feedwater mass flow", f, "feedFlow", "kg/h")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("Calculate feedwater enthalpy using IAPWS-IF97", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = autoFeed, onCheckedChange = { autoFeed = it })
                        }
                        if (autoFeed) {
                            InputField("Feedwater absolute pressure", f, "feedP", "bar abs")
                            InputField("Feedwater temperature", f, "feedT", "°C")
                        } else InputField("Feedwater enthalpy from verified table", f, "feedH", "kJ/kg")
                        Text("Automatic mode uses pressure and temperature with IAPWS-IF97; feedwater pressure is required for compressed-liquid accuracy.", style = MaterialTheme.typography.bodySmall)

                        Header("3 · Fuel input")
                        InputField("Fuel consumption", f, "fuelFlow", fuelRateUnit)
                        ChoiceRow("Fuel flow unit", fuelRateUnit, listOf("kg/h", "L/h")) { fuelRateUnit = it }
                        InputField("Fuel calorific value", f, "fuelCV", cvUnit)
                        ChoiceRow("Calorific value unit", cvUnit, listOf("kJ/kg", "MJ/kg", "kJ/L", "MJ/L")) { cvUnit = it }
                        ChoiceRow("Calorific value basis", basis, listOf("GCV / HHV", "NCV / LHV")) { basis = it }
                        Text("Enter a calorific value on the selected basis. The app does not convert GCV to NCV.", style = MaterialTheme.typography.bodySmall)

                        Header("4 · Other streams crossing the boundary")
                        InputField("Blowdown flow", f, "blowFlow", "kg/h")
                        InputField("Blowdown enthalpy", f, "blowH", "kJ/kg")
                        InputField("Attemperator spray flow", f, "sprayFlow", "kg/h")
                        InputField("Spray water enthalpy", f, "sprayH", "kJ/kg")
                        OutlinedTextField(value = f["notes"].orEmpty(), onValueChange = { f["notes"] = it }, label = { Text("Test notes (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                    }
                    1 -> {
                        Header("Indirect method · Enter loss percentages")
                        Text("Enter losses calculated from measured flue-gas/fuel/ash data using your approved test method. This screen sums supplied loss percentages; it does not calculate them from flue-gas chemistry.", style = MaterialTheme.typography.bodyMedium)
                        InputField("Dry flue-gas loss", f, "lossStack", "%")
                        InputField("Water vapor / fuel moisture loss", f, "lossMoisture", "%")
                        InputField("Incomplete combustion / unburned carbon", f, "lossUnburned", "%")
                        InputField("Radiation and convection loss", f, "lossRadiation", "%")
                        InputField("Other losses (avoid double counting)", f, "lossOther", "%")
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0E2))) {
                            Text("Use a consistent GCV/HHV or NCV/LHV basis for all losses. Follow the applicable standard; do not treat guessed percentages as measured results.", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {
                        Header("Calculate and review")
                        Button(onClick = { calculate() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Icon(Icons.Default.Calculate, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Calculate efficiency")
                        }
                        if (error.isNotBlank()) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                        direct?.let { r ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE2F0ED))) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Direct efficiency", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(String.format(Locale.US, "%.2f%%", r.efficiency), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    HorizontalDivider()
                                    ResultLine("Useful heat transfer", "%.1f kW".format(Locale.US, r.usefulKw))
                                    ResultLine("Fuel energy input", "%.1f kW".format(Locale.US, r.fuelKw))
                                    ResultLine("Net useful energy / kg main steam", "%.1f kJ/kg".format(Locale.US, r.qPerKg))
                                    ResultLine("Mass-balance difference", "%.1f kg/h".format(Locale.US, r.massDiff))
                                    Text("Fuel basis: $basis", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        indirect?.let { r ->
                            Card {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Indirect efficiency (from entered losses)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("%.2f%%".format(Locale.US, r.efficiency), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                                    Text("Total entered losses: %.2f%%".format(Locale.US, r.totalLoss), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        warnings.forEach { w ->
                            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0E2))) {
                                Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF9A4B19))
                                    Text(w, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Button(onClick = {
                            val r = direct
                            val text = buildString {
                                appendLine("BOILER EFFICIENCY REPORT")
                                appendLine("Report generated: ${java.time.LocalDate.now()}")
                                appendLine("Test ID: ${f["testId"].orEmpty()}")
                                appendLine("Boiler / unit: ${f["boilerId"].orEmpty()}")
                                appendLine("Plant / site: ${f["site"].orEmpty()}")
                                appendLine("Operator / engineer: ${f["operator"].orEmpty()}")
                                appendLine("Fuel type / test notes: ${f["notes"].orEmpty()}")
                                appendLine("Fuel basis: $basis")
                                appendLine("Steam flow (kg/h): ${f["steamFlow"]}")
                                appendLine("Steam pressure input (bar): ${f["steamP"]} ($pressureMode)")
                                appendLine("Atmospheric pressure used (bar abs): ${f["ambientPressure"]}")
                                appendLine("Steam temperature (C): ${f["steamT"]}")
                                appendLine("Steam enthalpy input (kJ/kg): ${f["steamH"]}")
                                appendLine("Feedwater flow (kg/h): ${f["feedFlow"]}")
                                appendLine("Feedwater pressure (bar abs): ${f["feedP"]}")
                                appendLine("Feedwater temperature (C): ${f["feedT"]}")
                                appendLine("Feedwater enthalpy input (kJ/kg): ${f["feedH"]}")
                                appendLine("Fuel rate: ${f["fuelFlow"]} $fuelRateUnit")
                                appendLine("Fuel calorific value: ${f["fuelCV"]} $cvUnit, basis $basis")
                                appendLine("Blowdown flow/enthalpy: ${f["blowFlow"]} kg/h / ${f["blowH"]} kJ/kg")
                                appendLine("Spray flow/enthalpy: ${f["sprayFlow"]} kg/h / ${f["sprayH"]} kJ/kg")
                                if (r != null) {
                                    appendLine("Direct efficiency (%): %.3f".format(Locale.US, r.efficiency))
                                    appendLine("Useful heat (kW): %.3f".format(Locale.US, r.usefulKw))
                                    appendLine("Fuel energy (kW): %.3f".format(Locale.US, r.fuelKw))
                                    appendLine("Mass-balance difference (kg/h): %.3f".format(Locale.US, r.massDiff))
                                }
                                indirect?.let { appendLine("Indirect efficiency from entered losses (%): %.3f".format(Locale.US, it.efficiency)) }
                                warnings.forEach { appendLine("NOTE: $it") }
                                appendLine("Property method: ${if (autoSteam || autoFeed) "IAPWS-IF97 Java library for automatically calculated enthalpy; manual inputs remain user-sourced" else "manual enthalpies; source should be recorded in test notes"}")
                                appendLine("Caution: calculation accuracy depends on calibrated instruments, synchronized readings, complete boundary and verified enthalpy/CV values. Not a certified performance test by itself.")
                            }
                            onShare(text)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Share, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Export / share report")
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable private fun Header(s: String) {
    Text(s, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}
@Composable private fun InputField(label: String, fields: MutableMap<String,String>, key: String, unit: String) {
    OutlinedTextField(value = fields[key].orEmpty(), onValueChange = { fields[key] = it }, label = { Text(label) },
        suffix = { Text(unit, style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (key in setOf("notes", "testId", "boilerId", "site", "operator")) KeyboardType.Text else KeyboardType.Decimal))
}
@Composable private fun ChoiceRow(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { option -> FilterChip(selected = selected == option, onClick = { onSelect(option) }, label = { Text(option) }) }
        }
    }
}
@Composable private fun ResultLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(10.dp))
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

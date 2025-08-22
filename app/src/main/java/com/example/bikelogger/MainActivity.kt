package com.example.bikelogger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import com.example.bikelogger.tracking.LocationService
import com.example.bikelogger.util.GpxWriter
import com.example.bikelogger.util.ShareUtil

class MainActivity : ComponentActivity() {
    private val vm: RideViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Osmdroid wymaga user agenta
        Configuration.getInstance().userAgentValue = packageName

        setContent {
            // Jeżeli masz swój Theme, możesz go tu owinąć
            Root(vm)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(vm: RideViewModel) {
    val nav = rememberNavController()
    val backstack by nav.currentBackStackEntryAsState()
    val route = backstack?.destination?.route ?: "counter"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (route == "counter") "Licznik rowerowy (OSM)" else "Przejazdy")
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = route == "counter",
                    onClick = { nav.navigate("counter") { launchSingleTop = true } },
                    label = { Text("Licznik") },
                    icon = {}
                )
                NavigationBarItem(
                    selected = route == "rides",
                    onClick = { nav.navigate("rides") { launchSingleTop = true } },
                    label = { Text("Przejazdy") },
                    icon = {}
                )
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "counter",
            modifier = Modifier.padding(padding)
        ) {
            composable("counter") { CounterScreen(vm) }
            composable("rides") { RidesScreen(vm) }
        }
    }
}

@Composable
fun CounterScreen(vm: RideViewModel) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()

    val requestPerms = rememberLauncherForPermissions(vm)
    LaunchedEffect(Unit) { requestPerms() }

    Column(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { context ->
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    controller.setZoom(16.0)
                    setMultiTouchControls(true)
                }
            },
            update = { map ->
                val polyline = (map.overlays.firstOrNull { it is Polyline } as? Polyline)
                    ?: Polyline().also { map.overlays.add(it) }
                polyline.setPoints(ui.path.map { GeoPoint(it.latitude, it.longitude) })
                ui.path.lastOrNull()?.let { last ->
                    map.controller.setCenter(GeoPoint(last.latitude, last.longitude))
                }
                map.invalidate()
            }
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Prędkość", style = MaterialTheme.typography.labelMedium)
            Text(
                "%.1f".format(ui.speedKmh),
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                when {
                    ui.isPaused -> "Auto-pauza"
                    ui.isRecording -> "Nagrywanie…"
                    else -> "Gotowy"
                },
                color = if (ui.isPaused) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary
            )
        }

        WindCard(ui)

        MetricsRow(
            distanceKm = ui.distanceKm,
            avgSpeedKmh = ui.avgMovingSpeedKmh,
            speedKmh = ui.speedKmh,
            elapsedText = ui.elapsedText,
            elevationGain = ui.elevGainM,
            vMax = ui.vMaxKmh
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!ui.isRecording) {
                Button(
                    onClick = {
                        startLocationService(ctx)
                        vm.start()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Start") }
            } else {
                Button(
                    onClick = {
                        vm.stop()
                        stopLocationService(ctx)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Stop") }

                OutlinedButton(
                    onClick = { vm.markLap() },
                    modifier = Modifier.weight(1f)
                ) { Text("Okrążenie") }
            }

            OutlinedButton(
                onClick = {
                    val file = GpxWriter.saveGpx(
                        ctx,
                        ui.path,
                        ui.startTimestampMs,
                        System.currentTimeMillis()
                    )
                    vm.onSavedAndIndex(ctx, file)
                },
                modifier = Modifier.weight(1f),
                enabled = ui.path.isNotEmpty()
            ) { Text("Zapisz GPX") }
        }

        if (ui.lastSavedFileName.isNotEmpty()) {
            Text(
                "Zapisano: ${ui.lastSavedFileName}",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun WindCard(ui: UiState) {
    val dirTo = if (ui.wind.dirDegFrom >= 0) (ui.wind.dirDegFrom + 180) % 360 else -1
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Wiatr", style = MaterialTheme.typography.labelMedium)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (!ui.wind.speedKmh.isNaN())
                        "Prędkość: %.1f km/h".format(ui.wind.speedKmh)
                    else "Prędkość: —",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (dirTo >= 0) "Kierunek: ${dirTo}° (dokąd wieje)" else "Kierunek: —",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (!ui.wind.gustKmh.isNaN()) {
                Text(
                    "Porywy: %.1f km/h".format(ui.wind.gustKmh),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (dirTo >= 0) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("↑", fontSize = 28.sp, modifier = Modifier.rotate(dirTo.toFloat()))
                }
            }
        }
    }
}

@Composable
private fun MetricsRow(
    distanceKm: Double,
    avgSpeedKmh: Double,
    speedKmh: Double,
    elapsedText: String,
    elevationGain: Int,
    vMax: Double
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Dystans", "%.2f km".format(distanceKm))
            Metric("Czas", elapsedText)
            Metric("Śr. pręd.", "%.1f km/h".format(avgSpeedKmh))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Metric("Vmax", "%.1f km/h".format(vMax))
            Metric("Przewyższenie", "${elevationGain} m")
            Metric("Aktualna", "%.1f km/h".format(speedKmh))
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun RidesScreen(vm: RideViewModel) {
    val ctx = LocalContext.current
    val rides by vm.rides.collectAsState()
    LaunchedEffect(Unit) { vm.loadRides(ctx) }

    if (rides.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Brak zapisanych przejazdów")
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(rides) { r ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { /* TODO: podgląd GPX */ }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(r.title, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "%s  •  %.2f km  •  %s  •  śr %.1f km/h  •  Vmax %.1f".format(
                            r.dateText, r.distanceKm, r.durationText, r.avgMovingKmh, r.vMaxKmh
                        )
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { ShareUtil.shareGpx(ctx, r.filePath) }) {
                            Text("Udostępnij GPX")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberLauncherForPermissions(vm: RideViewModel): () -> Unit {
    val ctx = LocalContext.current
    val launcher = remember {
        (ctx as ComponentActivity).registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { map ->
            val granted = map[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                map[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            vm.setLocationPermission(granted)
        }
    }
    return {
        launcher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }
}

private fun startLocationService(ctx: Context) {
    ContextCompat.startForegroundService(
        ctx,
        Intent(ctx, LocationService::class.java).apply { action = LocationService.ACTION_START }
    )
}

private fun stopLocationService(ctx: Context) {
    ctx.startService(
        Intent(ctx, LocationService::class.java).apply { action = LocationService.ACTION_STOP }
    )
}

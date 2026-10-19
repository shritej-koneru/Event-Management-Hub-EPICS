package com.example.eventmanagementhub.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.eventmanagementhub.BuildConfig
import com.example.eventmanagementhub.ui.theme.CardBorder
import com.example.eventmanagementhub.ui.theme.EventCardWhiteGradient
import com.example.eventmanagementhub.ui.theme.MutedText
import com.example.eventmanagementhub.ui.theme.NavyText
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

/**
 * Outdoor layer of the navigation screen, backed by the Google Maps SDK.
 *
 * Key policy: the SDK is only instantiated when [BuildConfig.MAPS_API_KEY] is
 * configured (local.properties / -P MAPS_API_KEY — never hard-coded). Without
 * a key the screen shows setup instructions instead of a broken map.
 *
 * Coordinates: the backend holds no outdoor (lat/lng) coordinates for venues,
 * so none are invented here. The map starts on the user's real location when
 * permission was granted; venue hand-off to outdoor turn-by-turn is done via
 * the Google Maps app deep link in the route sheet.
 */
@Composable
fun OutdoorMap(
    myLocation: LatLng?,
    modifier: Modifier = Modifier
) {
    if (BuildConfig.MAPS_API_KEY.isBlank()) {
        MapsKeySetupCard(modifier)
        return
    }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(myLocation ?: LatLng(0.0, 0.0), 1f)
    }
    LaunchedEffect(myLocation) {
        if (myLocation != null) {
            cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(myLocation, 16.0f))
        }
    }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false
        )
    ) {
        myLocation?.let { pos ->
            Marker(
                state = rememberMarkerState(position = pos),
                title = "You are here"
            )
        }
    }
}

/** Shown instead of the map when no Maps SDK key has been configured. */
@Composable
private fun MapsKeySetupCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("maps-key-setup-card"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(EventCardWhiteGradient)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Outdoor map needs a Google Maps key",
                style = MaterialTheme.typography.titleMedium,
                color = NavyText,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Enable the Maps SDK for Android in Google Cloud, then add\n" +
                    "MAPS_API_KEY=... to local.properties (or pass -P MAPS_API_KEY).\n\n" +
                    "Indoor navigation works without a key.",
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText,
                textAlign = TextAlign.Center
            )
        }
    }
}

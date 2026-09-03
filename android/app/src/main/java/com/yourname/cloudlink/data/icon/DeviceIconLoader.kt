package com.yourname.cloudlink.data.icon

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel that holds the per-screen map of model → icon_url loaded from
 * the cloud (which itself scrapes miot-spec.com).
 */
@HiltViewModel
class DeviceIconViewModel @Inject constructor(
    private val repo: DeviceIconRepository,
) : ViewModel() {
    private val _icons = MutableStateFlow<Map<String, String?>>(emptyMap())
    val icons: StateFlow<Map<String, String?>> = _icons.asStateFlow()

    fun ensureLoaded(model: String?) {
        if (model.isNullOrBlank()) return
        if (_icons.value.containsKey(model)) return
        viewModelScope.launch {
            val url = repo.getIconUrl(model)
            _icons.value = _icons.value + (model to url)
        }
    }

    fun ensureLoaded(models: Collection<String?>) {
        models.forEach { ensureLoaded(it) }
    }
}

/**
 * Renders the device's photo (from miot-spec.com) if we have a URL, or
 * the [fallback] Material icon otherwise. The Coil image loader caches
 * the binary so subsequent renders don't re-download.
 */
@Composable
fun DeviceIconImage(
    model: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    fallback: ImageVector = Icons.Filled.DeviceUnknown,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val vm: DeviceIconViewModel = hiltViewModel()
    val icons by vm.icons.collectAsState()
    LaunchedEffect(model) { vm.ensureLoaded(model) }
    val url = model?.let { icons[it] }
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                imageVector = fallback,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

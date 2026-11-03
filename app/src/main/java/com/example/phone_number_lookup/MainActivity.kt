package com.example.phone_number_lookup

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.phone_number_lookup.ui.components.CyberBackdrop
import com.example.phone_number_lookup.ui.components.Pill
import com.example.phone_number_lookup.ui.components.SegmentedTabs
import com.example.phone_number_lookup.ui.components.Space
import com.example.phone_number_lookup.ui.components.Tone
import com.example.phone_number_lookup.ui.screens.GitHubOsintScreen
import com.example.phone_number_lookup.ui.screens.OsintPilotScreen
import com.example.phone_number_lookup.ui.screens.PhoneLookupScreen
import com.example.phone_number_lookup.ui.screens.UsernameAndEmailOsintScreen
import com.example.phone_number_lookup.ui.theme.PhonenumberlookupTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeWebScraper.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            PhonenumberlookupTheme {
                NumberFinderApp()
            }
        }
    }
}

private data class AppTab(val label: String, val icon: ImageVector)

@Composable
fun NumberFinderApp(modifier: Modifier = Modifier) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val browserReq by NativeWebScraper.browserRequest.collectAsState()
    val tabs = listOf(
        AppTab("Pilot", Icons.Filled.Search),
        AppTab("Phone", Icons.Filled.Phone),
        AppTab("Profiles", Icons.Filled.Person),
        AppTab("GitHub", Icons.Filled.Search),
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppHeaderBar(
                tabs = tabs,
                selectedIndex = selectedTabIndex,
                onSelect = { selectedTabIndex = it },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            CyberBackdrop()
            val contentModifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
            when (selectedTabIndex) {
                0 -> OsintPilotScreen(modifier = contentModifier)
                1 -> PhoneLookupScreen(modifier = contentModifier)
                2 -> UsernameAndEmailOsintScreen(modifier = contentModifier)
                else -> GitHubOsintScreen(modifier = contentModifier)
            }
        }
    }

    browserReq?.let { req ->
        InAppOsintBrowserDialog(
            request = req,
            onDismiss = { NativeWebScraper.closeInApp() }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InAppOsintBrowserDialog(
    request: InAppBrowserRequest,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var currentUrl by remember(request.url) { mutableStateOf(request.url) }
    var isLoading by remember(request.url) { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Filled.Close, contentDescription = "Close in-app view")
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = "IN-APP NATIVE BROWSER",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = currentUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            TextButton(
                                onClick = {
                                    if (webViewRef?.canGoBack() == true) {
                                        webViewRef?.goBack()
                                    }
                                }
                            ) {
                                Text("Back")
                            }
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                                    }
                                }
                            ) {
                                Text("Ext ↗")
                            }
                        }
                        if (isLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                userAgentString =
                                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    req: WebResourceRequest?
                                ): Boolean {
                                    val target = req?.url?.toString() ?: return false
                                    if (!target.startsWith("http://") && !target.startsWith("https://")) {
                                        runCatching {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                                        }
                                        return true
                                    }
                                    currentUrl = target
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    isLoading = false
                                    if (!url.isNullOrBlank()) currentUrl = url
                                    CookieManager.getInstance().flush()
                                }
                            }
                            loadUrl(request.url)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AppHeaderBar(
    tabs: List<AppTab>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "OSINT",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "//PILOT",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Text(
                        text = "AUTONOMOUS INVESTIGATIONS · ON-DEVICE RECON",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.4.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        softWrap = true,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Pill("PILOT v1", tone = Tone.ACCENT)
            }

            SegmentedTabs(
                options = tabs.map { it.label },
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = Space.md),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
        }
    }
}

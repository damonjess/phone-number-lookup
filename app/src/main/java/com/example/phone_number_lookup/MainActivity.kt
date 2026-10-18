package com.example.phone_number_lookup

import android.os.Bundle
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.phone_number_lookup.ui.components.CyberBackdrop
import com.example.phone_number_lookup.ui.components.Pill
import com.example.phone_number_lookup.ui.components.SegmentedTabs
import com.example.phone_number_lookup.ui.components.Space
import com.example.phone_number_lookup.ui.components.Tone
import com.example.phone_number_lookup.ui.screens.GitHubOsintScreen
import com.example.phone_number_lookup.ui.screens.PhoneLookupScreen
import com.example.phone_number_lookup.ui.screens.UsernameAndEmailOsintScreen
import com.example.phone_number_lookup.ui.theme.PhonenumberlookupTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    val tabs = listOf(
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
            // Cap the reading width so the same layout still behaves on a folded inner display.
            val contentModifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
            when (selectedTabIndex) {
                0 -> PhoneLookupScreen(modifier = contentModifier)
                1 -> UsernameAndEmailOsintScreen(modifier = contentModifier)
                else -> GitHubOsintScreen(modifier = contentModifier)
            }
        }
    }
}

/**
 * Wordmark + mode switch in one block. The tabs live in the header rather than a bottom bar:
 * on tall gesture-navigation phones the bottom strip is exactly where the system competes for
 * touches, and a console reads top-down anyway.
 */
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
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "PHONE",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = "//OSINT",
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
                        text = "ON-DEVICE RECON · NO ACCOUNT",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.6.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Pill("LOCAL", tone = Tone.ACCENT)
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

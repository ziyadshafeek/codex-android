package dev.codex.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.codex.android.skills.Skill
import dev.codex.android.skills.SkillStore
import kotlinx.coroutines.launch

/**
 * Skills: every skill in [SkillStore], with a working enable/disable
 * toggle per skill, persisted via `SkillStore.save` (the same store
 * the agent loop reads, so a toggle here takes effect on the next
 * run). Built-ins missing from storage are backfilled first
 * ([SkillStore.seedBuiltins] inserts only missing ids and never
 * overwrites a user-edited row), so a phone that already had the
 * original 3 skills shows the newer ones too.
 *
 * No dead controls: the toggle is the only action, and it writes to
 * storage and refreshes the list on every flip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SkillStore(context) }
    val scope = rememberCoroutineScope()
    var skills by remember { mutableStateOf<List<Skill>>(emptyList()) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(store) {
        runCatching { store.seedBuiltins() }
        skills = runCatching { store.all() }.getOrElse {
            loadFailed = true
            emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Skills") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = "Skills bundle instructions and the tools the agent may use for one job. " +
                    "Turn a skill off and the agent will not offer it — or run it with the skill tool — until you turn it back on.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))
            if (loadFailed) {
                Text(
                    text = "Could not load skills. Storage may be unavailable — try again.",
                    modifier = Modifier.testTag("skills_error"),
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (skills.isEmpty()) {
                Text(
                    text = "No skills yet.",
                    modifier = Modifier.testTag("skills_empty"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("skills_list")) {
                    items(skills, key = { it.id }) { skill ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(skill.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    skill.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "Tools: ${skill.toolNames.joinToString(", ").ifEmpty { "none" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = skill.enabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        runCatching { store.save(skill.copy(enabled = enabled)) }
                                        skills = runCatching { store.all() }.getOrDefault(skills)
                                    }
                                },
                                modifier = Modifier.testTag("skill_toggle_${skill.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

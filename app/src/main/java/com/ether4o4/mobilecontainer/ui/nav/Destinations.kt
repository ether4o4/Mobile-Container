package com.ether4o4.mobilecontainer.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom-nav destinations.
 */
sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Terminal : Dest("terminal", "Terminal", Icons.Filled.Terminal)
    data object Models : Dest("models", "Models", Icons.Filled.PlayCircle)
    data object Persona : Dest("persona", "Persona", Icons.Filled.Person)
    data object Logs : Dest("logs", "Logs", Icons.Filled.Article)
}

val destinations = listOf(Dest.Terminal, Dest.Models, Dest.Persona, Dest.Logs)

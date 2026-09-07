package com.ether4o4.mobilecontainer.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ether4o4.mobilecontainer.ui.logs.LogsScreen
import com.ether4o4.mobilecontainer.ui.models.ModelsScreen
import com.ether4o4.mobilecontainer.ui.persona.PersonaScreen
import com.ether4o4.mobilecontainer.ui.terminal.TerminalScreen

@Composable
fun MCNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { d ->
                    NavigationBarItem(
                        selected = current == d.route,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) }
                    )
                }
            }
        }
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Dest.Terminal.route,
            modifier = Modifier.padding(inner)
        ) {
            composable(Dest.Terminal.route) { TerminalScreen() }
            composable(Dest.Models.route) { ModelsScreen() }
            composable(Dest.Persona.route) { PersonaScreen() }
            composable(Dest.Logs.route) { LogsScreen() }
        }
    }
}

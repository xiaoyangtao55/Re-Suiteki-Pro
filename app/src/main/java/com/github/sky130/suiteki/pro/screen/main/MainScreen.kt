package com.github.sky130.suiteki.pro.screen.main

import android.util.Log
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.github.sky130.suiteki.pro.screen.main.home.HomeScreen
import com.github.sky130.suiteki.pro.ui.theme.SuitekiTheme
import com.ramcosta.composedestinations.DestinationsNavHost
import com.ramcosta.composedestinations.animations.NavHostAnimatedDestinationStyle
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.ExternalDestination
import com.ramcosta.composedestinations.annotation.NavGraph
import com.ramcosta.composedestinations.annotation.NavHostGraph
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.NavGraphs
import com.ramcosta.composedestinations.generated.destinations.DeviceScreenDestination
import com.ramcosta.composedestinations.generated.destinations.FolderScreenDestination
import com.ramcosta.composedestinations.generated.destinations.HomeScreenDestination
import com.ramcosta.composedestinations.generated.navgraphs.MainNavGraph
import com.ramcosta.composedestinations.manualcomposablecalls.composable
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import com.ramcosta.composedestinations.navigation.dependency
import com.ramcosta.composedestinations.navigation.navGraph
import com.ramcosta.composedestinations.navigation.navigate
import com.ramcosta.composedestinations.navigation.popUpTo
import com.ramcosta.composedestinations.result.ResultRecipient
import com.ramcosta.composedestinations.scope.resultRecipient
import com.ramcosta.composedestinations.spec.DirectionDestinationSpec
import java.io.File

@Composable
@Destination<RootGraph>(start = true)
fun MainScreen(
    navigator: DestinationsNavigator,
    resultRecipient: ResultRecipient<FolderScreenDestination, File>
) {
    val navController = rememberNavController()
    Scaffold(bottomBar = {
        BottomBar(navController = navController)
    }) { innerPadding ->
        // 主页需要跳到同一层图里的「设备」页。它拿到的 ExternalNavigator 是外层
        // （RootGraph）的控制器，拿去 navigate(MainGraph 里的路由) 会直接抛
        // "destination cannot be found"——之前点「添加设备」闪退就是这个。
        CompositionLocalProvider(LocalMainNavController provides navController) {
            DestinationsNavHost(
                navGraph = NavGraphs.main,
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                navController = navController,
                defaultTransitions = BasicTransitions(enterTransition = {
                    fadeIn(animationSpec = tween(400), initialAlpha = 0f)
                },
                    exitTransition = {
                        fadeOut(animationSpec = tween(400), targetAlpha = 0f)
                    }),
                dependenciesContainerBuilder = {
                    dependency(createExternalNavigator(navigator))
                }
            ) {
                composable(HomeScreenDestination) {
                    HomeScreen(
                        createExternalNavigator(navigator),
                        resultRecipient = resultRecipient
                    )
                }
            }
        }
    }
}

/** 底部页签所在的 NavController（MainGraph 那层）。 */
val LocalMainNavController = staticCompositionLocalOf<NavHostController> {
    error("LocalMainNavController 未提供")
}

class BasicTransitions(
    override val enterTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition,
    override val exitTransition: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition
) : NavHostAnimatedDestinationStyle()

@Composable
fun BottomBar(
    navController: NavHostController
) {
    // 用"当前页"而不是"是否在回退栈里"判断选中。主页是起始页，从主页跳到设备页后
    // 它仍然躺在回退栈里，isRouteOnBackStack 会一直返回 true，导致点「主页」直接 return，
    // 人就被卡在设备页出不来了。
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route

    NavigationBar {
        MainItem.entries.forEach { destination ->
            val isCurrent = currentRoute == destination.direction.route
            NavigationBarItem(
                selected = isCurrent,
                onClick = {
                    if (isCurrent) {
                        return@NavigationBarItem
                    }
                    navController.navigate(destination.direction) {
                        // 回到起始页（主页），把上面压的页全部弹掉；
                        // 各页状态存下来，切回来时还原
                        popUpTo(HomeScreenDestination) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    Icon(
                        destination.icon, contentDescription = destination.title
                    )
                },
                label = { Text(destination.title) },
            )
        }
    }
}

enum class MainItem(
    val direction: DirectionDestinationSpec, val title: String, val icon: ImageVector
) {
    Home(HomeScreenDestination, "主页", Icons.Default.Home),

    Device(DeviceScreenDestination, "设备", Icons.Default.Watch),

}

@NavHostGraph(route = "main")
annotation class MainGraph

data class ExternalNavigator(val navigator: DestinationsNavigator)

fun createExternalNavigator(navController: DestinationsNavigator) = ExternalNavigator(navController)
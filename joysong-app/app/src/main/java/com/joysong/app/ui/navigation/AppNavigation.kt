package com.joysong.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.hilt.navigation.compose.hiltViewModel
import com.joysong.app.R
import com.joysong.app.ui.aiagent.AiAgentScreen
import com.joysong.app.ui.components.LocalMessageInputFocusHandler
import com.joysong.app.ui.auth.AuthState
import com.joysong.app.ui.auth.AuthUiState
import com.joysong.app.ui.auth.AuthViewModel
import com.joysong.app.ui.auth.LoginScreen
import com.joysong.app.ui.coupon.CouponListScreen
import com.joysong.app.ui.detail.AppointmentScreen
import com.joysong.app.ui.detail.ArticleDetailScreen
import com.joysong.app.ui.detail.DiaryDetailScreen
import com.joysong.app.ui.detail.DoctorAllDiariesScreen
import com.joysong.app.ui.detail.DoctorAllProjectsScreen
import com.joysong.app.ui.detail.DoctorDetailScreen
import com.joysong.app.ui.detail.InstitutionAllDiariesScreen
import com.joysong.app.ui.detail.InstitutionAllDoctorsScreen
import com.joysong.app.ui.detail.InstitutionAllProjectsScreen
import com.joysong.app.ui.detail.InstitutionAllReviewsScreen
import com.joysong.app.ui.detail.InstitutionDetailScreen
import com.joysong.app.ui.detail.InstitutionProjectDetailScreen
import com.joysong.app.ui.detail.ProjectAllDiariesScreen
import com.joysong.app.ui.detail.ProjectAllInstitutionsScreen
import com.joysong.app.ui.detail.ProjectDetailScreen
import com.joysong.app.ui.discover.DiscoverScreen
import com.joysong.app.ui.discover.DiscoverViewModel
import com.joysong.app.ui.discover.ProjectFilterScreen
import com.joysong.app.ui.dm.DmChatScreen
import com.joysong.app.ui.home.HomeScreen
import com.joysong.app.ui.notification.MessagesScreen
import com.joysong.app.ui.booking.BookingConfirmScreen
import com.joysong.app.ui.order.AppointmentSuccessScreen
import com.joysong.app.ui.order.OrderDetailScreen
import com.joysong.app.ui.order.OrderListScreen
import com.joysong.app.ui.order.OrderPaymentScreen
import com.joysong.app.ui.order.PaymentScreen
import com.joysong.app.ui.order.RefundApplyScreen
import com.joysong.app.ui.order.ReviewOrderScreen
import com.joysong.app.ui.profile.AboutUsScreen
import com.joysong.app.ui.profile.AccountSecurityScreen
import com.joysong.app.ui.profile.BindEmailScreen
import com.joysong.app.ui.profile.BindPhoneScreen
import com.joysong.app.ui.profile.ChangePasswordScreen
import com.joysong.app.ui.profile.ProfileViewModel
import com.joysong.app.ui.profile.ResetPasswordScreen
import com.joysong.app.ui.profile.CustomerServiceScreen
import com.joysong.app.ui.profile.EditProfileScreen
import com.joysong.app.ui.profile.FavoritesScreen
import com.joysong.app.ui.profile.HelpFeedbackScreen
import com.joysong.app.ui.profile.IdentityApplicationScreen
import com.joysong.app.ui.profile.MyDiariesScreen
import com.joysong.app.ui.profile.OfficialVerificationScreen
import com.joysong.app.ui.profile.ProfileScreen
import com.joysong.app.ui.profile.PublishDiaryScreen
import com.joysong.app.ui.profile.SelectDoctorScreen
import com.joysong.app.ui.profile.SelectInstitutionScreen
import com.joysong.app.ui.profile.SelectProjectScreen
import com.joysong.app.ui.profile.UserProfileScreen
import org.json.JSONObject
import com.joysong.app.ui.profile.SettingsScreen
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextSecondary
import java.net.URLDecoder

// ── Bottom navigation tab definitions ─────────────────────────────────────────

private data class BottomNavItem(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.Home.route, Icons.Outlined.Home, R.string.nav_home),
    BottomNavItem("discover", Icons.Outlined.Search, R.string.nav_discover),
    BottomNavItem(Routes.AiAgent.route, Icons.Outlined.ChatBubbleOutline, R.string.nav_ai),
    BottomNavItem(Routes.Profile.route, Icons.Outlined.Person, R.string.nav_profile),
)

// ── Main entry point ─────────────────────────────────────────────────────────

@Composable
fun AppNavigation() {
    val outerNavController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val authState by authViewModel.authState.collectAsState()

    NavHost(
        navController = outerNavController,
        startDestination = Routes.Login.route
    ) {
        composable(Routes.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    outerNavController.navigate(Routes.Main.route) {
                        popUpTo(Routes.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.Main.route) {
            MainScreen(
                outerNavController,
                onLogout = { authViewModel.logout() },
                onAccountDeleted = { authViewModel.deleteAccount() }
            )
        }
    }

    // 监听认证状态变化：已登录→跳主页，token 失效→跳回登录
    LaunchedEffect(authState) {
        when (authState) {
            is AuthState.Authenticated -> {
                // Token 存在（冷启动恢复 或 刚登录成功）→ 确保在主页
                outerNavController.navigate(Routes.Main.route) {
                    popUpTo(Routes.Login.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
            is AuthState.Unauthenticated -> {
                // Token 被清除（401 或 主动登出）→ 回到登录页
                outerNavController.navigate(Routes.Login.route) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
            is AuthState.Loading -> { /* 正在检查 Token，不做任何操作 */ }
        }
    }
}

// ── Main screen with bottom navigation ────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainScreen(
    outerNavController: NavHostController,
    onLogout: () -> Unit,
    onAccountDeleted: () -> Unit
) {
    val innerNavController = rememberNavController()
    val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val currentRoute = currentDestination?.route?.substringBefore("?")

    var isMessageInputFocused by remember { mutableStateOf(false) }
    val reportMessageInputFocus: (Boolean) -> Unit = remember {
        { focused -> isMessageInputFocused = focused }
    }
    // isImeVisible changes once per visibility transition. Avoid observing the
    // animated IME bottom inset, which forced the root Scaffold to remeasure on every frame.
    val isKeyboardVisible = WindowInsets.isImeVisible
    val isTopLevelDestination = bottomNavItems.any { currentRoute == it.route.substringBefore("?") }

    LaunchedEffect(currentRoute) {
        isMessageInputFocused = false
    }

    CompositionLocalProvider(LocalMessageInputFocusHandler provides reportMessageInputFocus) {
        Scaffold(
            bottomBar = {
            if (isTopLevelDestination && !isMessageInputFocused && !isKeyboardVisible) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected = currentRoute == item.route.substringBefore("?")
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                innerNavController.navigate(item.route) {
                                    popUpTo(Routes.Home.route) {
                                        inclusive = false
                                    }
                                    launchSingleTop = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.labelRes)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Primary,
                                selectedTextColor = Primary,
                                unselectedIconColor = TextHint,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = androidx.compose.ui.graphics.Color.Transparent
                            )
                        )
                    }
                }
            }
            }
        ) { innerPadding ->
        NavHost(
            navController = innerNavController,
            startDestination = Routes.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            // ── Tab destinations ────────────────────────────────────────────────

            composable(Routes.Home.route) {
                HomeScreen(
                    onProjectClick = { id ->
                        innerNavController.navigate(Routes.ProjectDetail.createRoute(id))
                    },
                    onArticleClick = { id ->
                        innerNavController.navigate(Routes.ArticleDetail.createRoute(id))
                    },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    },
                    onInstitutionClick = { id ->
                        innerNavController.navigate(Routes.InstitutionDetail.createRoute(id))
                    },
                    onDoctorClick = { id ->
                        innerNavController.navigate(Routes.DoctorDetail.createRoute(id))
                    },
                    onInstitutionProjectClick = { institutionId, projectId ->
                        innerNavController.navigate(Routes.InstitutionProjectDetail.createRoute(institutionId, projectId))
                    },
                    onViewAllClick = { tabIndex ->
                        innerNavController.navigate(Routes.Discover.createRoute(tabIndex)) {
                            popUpTo(Routes.Home.route) {
                                inclusive = false
                            }
                            launchSingleTop = true
                        }
                    },
                    onAuthorClick = { id ->
                        innerNavController.navigate(Routes.DoctorDetail.createRoute(id))
                    },
                    onSearchClick = {
                        innerNavController.navigate("discover") {
                            popUpTo(Routes.Home.route) {
                                inclusive = false
                            }
                            launchSingleTop = true
                        }
                    },
                    onNotificationClick = {
                        innerNavController.navigate(Routes.Messages.route)
                    }
                )
            }

            composable(
                route = Routes.Discover.route,
                arguments = listOf(
                    navArgument("tab") { type = NavType.IntType; defaultValue = 0 }
                )
            ) { backStackEntry ->
                val initialTab = backStackEntry.arguments?.getInt("tab") ?: 0
                DiscoverScreen(
                    initialTab = initialTab,
                    onProjectClick = { id ->
                        innerNavController.navigate(Routes.ProjectDetail.createRoute(id))
                    },
                    onInstitutionClick = { id ->
                        innerNavController.navigate(Routes.InstitutionDetail.createRoute(id))
                    },
                    onDoctorClick = { id ->
                        innerNavController.navigate(Routes.DoctorDetail.createRoute(id))
                    },
                    onArticleClick = { id ->
                        innerNavController.navigate(Routes.ArticleDetail.createRoute(id))
                    },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    },
                    onFilterClick = {
                        innerNavController.navigate(Routes.ProjectFilter.route)
                    },
                    onInstitutionProjectClick = { institutionId, projectId ->
                        innerNavController.navigate(Routes.InstitutionProjectDetail.createRoute(institutionId, projectId))
                    }
                )
            }

            composable(Routes.ProjectFilter.route) { backStackEntry ->
                // Share the DiscoverViewModel with the Discover composable's back stack entry
                val discoverEntry = innerNavController.getBackStackEntry(Routes.Discover.route)
                val discoverViewModel: DiscoverViewModel = hiltViewModel(discoverEntry)
                val uiState by discoverViewModel.uiState.collectAsState()
                ProjectFilterScreen(
                    filterOptions = uiState.filterOptions,
                    initialCategories = uiState.selectedCategories,
                    initialTags = uiState.selectedTags,
                    initialCities = uiState.selectedCities,
                    onConfirm = { categories, tags, cities ->
                        discoverViewModel.applyFilters(categories, tags, cities)
                        innerNavController.popBackStack()
                    },
                    onBack = { innerNavController.popBackStack() }
                )
            }

            composable(
                route = Routes.AiAgent.route,
                arguments = listOf(
                    navArgument("contextType") { defaultValue = "" },
                    navArgument("contextId") { defaultValue = "" },
                    navArgument("contextName") { defaultValue = "" },
                    navArgument("role") { defaultValue = "" }
                )
            ) { backStackEntry ->
                val contextType = backStackEntry.arguments?.getString("contextType") ?: ""
                val contextId = backStackEntry.arguments?.getString("contextId") ?: ""
                val contextName = backStackEntry.arguments?.getString("contextName")?.let {
                    URLDecoder.decode(it, "UTF-8")
                } ?: ""
                val role = backStackEntry.arguments?.getString("role") ?: ""
                AiAgentScreen(
                    contextType = contextType,
                    contextId = contextId,
                    contextName = contextName,
                    role = role,
                    onOpenEntity = { item ->
                        when (item.type) {
                            "INSTITUTION" -> innerNavController.navigate(Routes.InstitutionDetail.createRoute(item.id))
                            "DOCTOR" -> innerNavController.navigate(Routes.DoctorDetail.createRoute(item.id))
                            "PROJECT" -> innerNavController.navigate(Routes.ProjectDetail.createRoute(item.id))
                            "INSTITUTION_PROJECT" -> if (item.institutionId != null && item.projectId != null) {
                                innerNavController.navigate(Routes.InstitutionProjectDetail.createRoute(item.institutionId, item.projectId))
                            }
                        }
                    },
                    onHumanChat = { item ->
                        when (item.type) {
                            "DOCTOR" -> innerNavController.navigate(Routes.DmChat.createRoute(item.id, "doctor"))
                            else -> item.institutionId?.let { id ->
                                innerNavController.navigate(Routes.DmChat.createRoute(id, "institution"))
                            }
                        }
                    }
                )
            }

            composable(Routes.Profile.route) {
                ProfileScreen(
                    onOrdersClick = {
                        innerNavController.navigate(Routes.OrderList.route)
                    },
                    onDiariesClick = {
                        innerNavController.navigate(Routes.MyDiaries.route)
                    },
                    onJourneyClick = {},
                    onFavoritesClick = {
                        innerNavController.navigate(Routes.Favorites.route)
                    },
                    onCustomerServiceClick = {
                        innerNavController.navigate(Routes.CustomerService.route)
                    },
                    onHelpClick = {
                        innerNavController.navigate(Routes.HelpFeedback.route)
                    },
                    onAboutClick = {
                        innerNavController.navigate(Routes.AboutUs.route)
                    },
                    onSettingsClick = {
                        innerNavController.navigate(Routes.Settings.route)
                    },
                    onAccountSecurityClick = {
                        innerNavController.navigate(Routes.AccountSecurity.route)
                    },
                    onEditProfileClick = {
                        innerNavController.navigate(Routes.EditProfile.route)
                    },
                    onLogout = onLogout
                )
            }

            // ── Detail destinations ─────────────────────────────────────────────

            composable(
                route = Routes.ProjectDetail.route,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { backStackEntry ->
                val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
                ProjectDetailScreen(
                    projectId = projectId,
                    onBackClick = { innerNavController.popBackStack() },
                    onAiChatClick = { project ->
                        innerNavController.navigate(
                            Routes.AiAgent.createRoute(
                                contextType = "project",
                                contextId = project.id,
                                contextName = project.name
                            )
                        )
                    },
                    onBookClick = { project ->
                        innerNavController.navigate(
                            Routes.Appointment.createRoute(project.id, "")
                        )
                    },
                    onInstitutionClick = { id ->
                        innerNavController.navigate(Routes.InstitutionDetail.createRoute(id))
                    },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    },
                    onNavigateToAllDiaries = { pid ->
                        innerNavController.navigate(Routes.ProjectAllDiaries.createRoute(pid))
                    },
                    onNavigateToAllInstitutions = { pid ->
                        innerNavController.navigate(Routes.ProjectAllInstitutions.createRoute(pid))
                    },
                    onInstitutionProjectClick = { institutionId, projectId ->
                        innerNavController.navigate(
                            Routes.InstitutionProjectDetail.createRoute(institutionId, projectId)
                        )
                    }
                )
            }

            composable(
                route = Routes.InstitutionDetail.route,
                arguments = listOf(navArgument("institutionId") { type = NavType.StringType })
            ) { backStackEntry ->
                val institutionId = backStackEntry.arguments?.getString("institutionId") ?: ""
                InstitutionDetailScreen(
                    institutionId = institutionId,
                    onBackClick = { innerNavController.popBackStack() },
                    onAiChatClick = { institution ->
                        innerNavController.navigate(
                            Routes.AiAgent.createRoute(
                                contextType = "institution",
                                contextId = institution.id,
                                contextName = institution.name
                            )
                        )
                    },
                    onProjectClick = { projectId ->
                        innerNavController.navigate(
                            Routes.InstitutionProjectDetail.createRoute(institutionId, projectId)
                        )
                    },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    },
                    onDoctorClick = { id ->
                        innerNavController.navigate(Routes.DoctorDetail.createRoute(id))
                    },
                    onNavigateToAllDiaries = { iid ->
                        innerNavController.navigate(Routes.InstitutionAllDiaries.createRoute(iid))
                    },
                    onNavigateToAllProjects = { iid ->
                        innerNavController.navigate(Routes.InstitutionAllProjects.createRoute(iid))
                    },
                    onNavigateToAllDoctors = { iid ->
                        innerNavController.navigate(Routes.InstitutionAllDoctors.createRoute(iid))
                    },
                    onNavigateToAllReviews = { iid ->
                        innerNavController.navigate(Routes.InstitutionAllReviews.createRoute(iid))
                    },
                    onConsultClick = { institution ->
                        innerNavController.navigate(Routes.DmChat.createRoute(institution.id, "institution"))
                    }
                )
            }

            composable(
                route = Routes.DoctorDetail.route,
                arguments = listOf(navArgument("doctorId") { type = NavType.StringType })
            ) { backStackEntry ->
                val doctorId = backStackEntry.arguments?.getString("doctorId") ?: ""
                DoctorDetailScreen(
                    doctorId = doctorId,
                    onBackClick = { innerNavController.popBackStack() },
                    onAiChatClick = { doctor ->
                        innerNavController.navigate(
                            Routes.AiAgent.createRoute(
                                contextType = "doctor",
                                contextId = doctor.id,
                                contextName = doctor.name
                            )
                        )
                    },
                    onProjectClick = { institutionId, projectId ->
                        innerNavController.navigate(
                            Routes.InstitutionProjectDetail.createRoute(institutionId, projectId)
                        )
                    },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    },
                    onInstitutionClick = { id ->
                        innerNavController.navigate(Routes.InstitutionDetail.createRoute(id))
                    },
                    onNavigateToAllDiaries = { did ->
                        innerNavController.navigate(Routes.DoctorAllDiaries.createRoute(did))
                    },
                    onNavigateToAllProjects = { did ->
                        innerNavController.navigate(Routes.DoctorAllProjects.createRoute(did))
                    },
                    onMessageClick = { targetId ->
                        innerNavController.navigate(Routes.DmChat.createRoute(targetId, "doctor"))
                    }
                )
            }

            composable(
                route = Routes.ArticleDetail.route,
                arguments = listOf(navArgument("articleId") { type = NavType.StringType })
            ) { backStackEntry ->
                val articleId = backStackEntry.arguments?.getString("articleId") ?: ""
                ArticleDetailScreen(
                    articleId = articleId,
                    onBackClick = { innerNavController.popBackStack() },
                    onDoctorClick = { id ->
                        innerNavController.navigate(Routes.DoctorDetail.createRoute(id))
                    }
                )
            }

            composable(
                route = Routes.DiaryDetail.route,
                arguments = listOf(navArgument("diaryId") { type = NavType.StringType })
            ) { backStackEntry ->
                val diaryId = backStackEntry.arguments?.getString("diaryId") ?: ""
                DiaryDetailScreen(
                    diaryId = diaryId,
                    onBackClick = { innerNavController.popBackStack() },
                    onProjectClick = { id ->
                        innerNavController.navigate(Routes.ProjectDetail.createRoute(id))
                    },
                    onDoctorClick = { id ->
                        innerNavController.navigate(Routes.DoctorDetail.createRoute(id))
                    },
                    onInstitutionClick = { id ->
                        innerNavController.navigate(Routes.InstitutionDetail.createRoute(id))
                    },
                    onUserClick = { userId ->
                        innerNavController.navigate(Routes.UserProfile.createRoute(userId))
                    }
                )
            }

            composable(
                route = Routes.InstitutionProjectDetail.route,
                arguments = listOf(
                    navArgument("institutionId") { type = NavType.StringType },
                    navArgument("projectId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val institutionId = backStackEntry.arguments?.getString("institutionId") ?: ""
                val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
                InstitutionProjectDetailScreen(
                    institutionId = institutionId,
                    projectId = projectId,
                    onBackClick = { innerNavController.popBackStack() },
                    onAiChatClick = { institutionProjectId, projectName ->
                        innerNavController.navigate(
                            Routes.AiAgent.createRoute(
                                contextType = "institution_project",
                                contextId = institutionProjectId,
                                contextName = projectName
                            )
                        )
                    },
                    onInstitutionClick = { id ->
                        innerNavController.navigate(Routes.InstitutionDetail.createRoute(id))
                    },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    },
                    onNavigateToAllDiaries = { pid ->
                        innerNavController.navigate(Routes.ProjectAllDiaries.createRoute(pid))
                    },
                    onNavigateToAllInstitutions = { pid ->
                        innerNavController.navigate(Routes.ProjectAllInstitutions.createRoute(pid))
                    },
                    onDoctorClick = { id ->
                        innerNavController.navigate(Routes.DoctorDetail.createRoute(id))
                    },
                    onNavigateToAllDoctors = { iid ->
                        innerNavController.navigate(Routes.InstitutionAllDoctors.createRoute(iid))
                    },
                    onBookProjectClick = { institutionProjectId, pid, iid ->
                        innerNavController.navigate(
                            Routes.BookingConfirm.createRoute(institutionProjectId, pid, iid)
                        )
                    }
                )
            }

            composable(
                route = Routes.ProjectAllDiaries.route,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { backStackEntry ->
                val pid = backStackEntry.arguments?.getString("projectId") ?: ""
                ProjectAllDiariesScreen(
                    projectId = pid,
                    onBackClick = { innerNavController.popBackStack() },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    }
                )
            }

            composable(
                route = Routes.ProjectAllInstitutions.route,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType })
            ) { backStackEntry ->
                val pid = backStackEntry.arguments?.getString("projectId") ?: ""
                ProjectAllInstitutionsScreen(
                    projectId = pid,
                    onBackClick = { innerNavController.popBackStack() },
                    onInstitutionClick = { id ->
                        innerNavController.navigate(Routes.InstitutionDetail.createRoute(id))
                    },
                    onInstitutionProjectClick = { institutionId, projectId ->
                        innerNavController.navigate(
                            Routes.InstitutionProjectDetail.createRoute(institutionId, projectId)
                        )
                    }
                )
            }

            composable(
                route = Routes.DoctorAllDiaries.route,
                arguments = listOf(navArgument("doctorId") { type = NavType.StringType })
            ) { backStackEntry ->
                val did = backStackEntry.arguments?.getString("doctorId") ?: ""
                DoctorAllDiariesScreen(
                    doctorId = did,
                    onBackClick = { innerNavController.popBackStack() },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    }
                )
            }

            composable(
                route = Routes.DoctorAllProjects.route,
                arguments = listOf(navArgument("doctorId") { type = NavType.StringType })
            ) { backStackEntry ->
                val did = backStackEntry.arguments?.getString("doctorId") ?: ""
                DoctorAllProjectsScreen(
                    doctorId = did,
                    onBackClick = { innerNavController.popBackStack() },
                    onProjectClick = { institutionId, projectId ->
                        innerNavController.navigate(
                            Routes.InstitutionProjectDetail.createRoute(institutionId, projectId)
                        )
                    }
                )
            }

            composable(
                route = Routes.InstitutionAllDiaries.route,
                arguments = listOf(navArgument("institutionId") { type = NavType.StringType })
            ) { backStackEntry ->
                val iid = backStackEntry.arguments?.getString("institutionId") ?: ""
                InstitutionAllDiariesScreen(
                    institutionId = iid,
                    onBackClick = { innerNavController.popBackStack() },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    }
                )
            }

            composable(
                route = Routes.InstitutionAllProjects.route,
                arguments = listOf(navArgument("institutionId") { type = NavType.StringType })
            ) { backStackEntry ->
                val iid = backStackEntry.arguments?.getString("institutionId") ?: ""
                InstitutionAllProjectsScreen(
                    institutionId = iid,
                    onBackClick = { innerNavController.popBackStack() },
                    onProjectClick = { projectId ->
                        innerNavController.navigate(
                            Routes.InstitutionProjectDetail.createRoute(iid, projectId)
                        )
                    }
                )
            }

            composable(
                route = Routes.InstitutionAllDoctors.route,
                arguments = listOf(navArgument("institutionId") { type = NavType.StringType })
            ) { backStackEntry ->
                val iid = backStackEntry.arguments?.getString("institutionId") ?: ""
                InstitutionAllDoctorsScreen(
                    institutionId = iid,
                    onBackClick = { innerNavController.popBackStack() },
                    onDoctorClick = { id ->
                        innerNavController.navigate(Routes.DoctorDetail.createRoute(id))
                    }
                )
            }

            composable(
                route = Routes.InstitutionAllReviews.route,
                arguments = listOf(navArgument("institutionId") { type = NavType.StringType })
            ) { backStackEntry ->
                val iid = backStackEntry.arguments?.getString("institutionId") ?: ""
                InstitutionAllReviewsScreen(
                    institutionId = iid,
                    onBackClick = { innerNavController.popBackStack() }
                )
            }

            // ── User Profile ──────────────────────────────────────────────────────

            composable(
                route = Routes.UserProfile.route,
                arguments = listOf(navArgument("userId") { type = NavType.StringType })
            ) { backStackEntry ->
                val userId = backStackEntry.arguments?.getString("userId") ?: ""
                UserProfileScreen(
                    userId = userId,
                    onBackClick = { innerNavController.popBackStack() },
                    onDiaryClick = { diaryId ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(diaryId))
                    },
                    onMessageClick = { targetUserId ->
                        innerNavController.navigate(Routes.DmChat.createRoute(targetUserId, "user"))
                    }
                )
            }

            // ── DM Chat ──────────────────────────────────────────────────────

            composable(
                route = "dm_chat/{targetId}?type={type}",
                arguments = listOf(
                    navArgument("targetId") { type = NavType.StringType },
                    navArgument("type") { defaultValue = "user" }
                )
            ) { backStackEntry ->
                val targetId = backStackEntry.arguments?.getString("targetId") ?: ""
                val type = backStackEntry.arguments?.getString("type") ?: "user"
                DmChatScreen(
                    targetId = targetId,
                    type = type,
                    onBackClick = { innerNavController.popBackStack() },
                    onInstitutionClick = { instId ->
                        innerNavController.navigate(Routes.InstitutionDetail.createRoute(instId))
                    },
                    onUserClick = { userId -> innerNavController.navigate(Routes.UserProfile.createRoute(userId)) }
                )
            }

            // ── Order flow ──────────────────────────────────────────────────────

            composable(Routes.OrderList.route) {
                OrderListScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onOrderClick = { id ->
                        innerNavController.navigate(Routes.OrderDetail.createRoute(id))
                    },
                    onPayClick = { id ->
                        innerNavController.navigate(Routes.OrderDetail.createRoute(id))
                    },
                    onRefundClick = { id ->
                        innerNavController.navigate(Routes.RefundApply.createRoute(id))
                    },
                    onReviewClick = { id ->
                        innerNavController.navigate(Routes.ReviewOrder.createRoute(id))
                    },
                    onViewReviewClick = { id ->
                        innerNavController.navigate(Routes.ReviewOrder.createRoute(id, editMode = true))
                    },
                    onWriteDiaryClick = { id ->
                        innerNavController.navigate(Routes.PublishDiary.createRoute(id))
                    }
                )
            }

            composable(
                route = Routes.OrderDetail.route,
                arguments = listOf(navArgument("orderId") { type = NavType.StringType })
            ) { backStackEntry ->
                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                OrderDetailScreen(
                    orderId = orderId,
                    navController = innerNavController
                )
            }

            composable(
                route = Routes.Appointment.route,
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("institutionId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
                val institutionId = backStackEntry.arguments?.getString("institutionId") ?: ""
                AppointmentScreen(
                    projectId = projectId,
                    institutionId = institutionId,
                    project = null,
                    onBackClick = { innerNavController.popBackStack() },
                    onGoToPay = { doctorName, time ->
                        innerNavController.navigate(
                            Routes.Payment.createRoute(projectId, institutionId, doctorName, time)
                        )
                    }
                )
            }

            composable(
                route = Routes.Payment.route,
                arguments = listOf(
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("institutionId") { type = NavType.StringType },
                    navArgument("doctorName") { type = NavType.StringType },
                    navArgument("appointmentTime") { type = NavType.StringType }
                )
            ) {
                PaymentScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onPaySuccess = { orderId, institutionName, doctorName, appointmentTime ->
                        innerNavController.navigate(
                            Routes.AppointmentSuccess.createRoute(institutionName, doctorName, appointmentTime)
                        ) {
                            popUpTo(Routes.Appointment.route) { inclusive = true }
                        }
                    },
                    onSelectCouponClick = {
                        val fee = 0.0
                        innerNavController.navigate(Routes.CouponList.createRoute(fee))
                    }
                )
            }

            composable(
                route = Routes.AppointmentSuccess.route,
                arguments = listOf(
                    navArgument("institutionName") { type = NavType.StringType },
                    navArgument("doctorName") { type = NavType.StringType },
                    navArgument("appointmentTime") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val institutionName = backStackEntry.arguments?.getString("institutionName")?.let {
                    URLDecoder.decode(it, "UTF-8")
                } ?: ""
                val doctorName = backStackEntry.arguments?.getString("doctorName")?.let {
                    URLDecoder.decode(it, "UTF-8")
                } ?: ""
                val appointmentTime = backStackEntry.arguments?.getString("appointmentTime")?.let {
                    URLDecoder.decode(it, "UTF-8")
                } ?: ""
                AppointmentSuccessScreen(
                    institutionName = institutionName,
                    doctorName = doctorName,
                    appointmentTime = appointmentTime,
                    onBackClick = { innerNavController.popBackStack() },
                    onViewOrderClick = {
                        innerNavController.navigate(Routes.OrderList.route) {
                            popUpTo(Routes.Home.route)
                        }
                    },
                    onBackToHomeClick = {
                        innerNavController.navigate(Routes.Home.route) {
                            popUpTo(Routes.Home.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = Routes.RefundApply.route,
                arguments = listOf(navArgument("orderId") { type = NavType.StringType })
            ) { backStackEntry ->
                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                RefundApplyScreen(
                    orderId = orderId,
                    navController = innerNavController
                )
            }

            composable(
                route = Routes.ReviewOrder.route,
                arguments = listOf(
                    navArgument("orderId") { type = NavType.StringType },
                    navArgument("editMode") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { backStackEntry ->
                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                val editMode = backStackEntry.arguments?.getBoolean("editMode") ?: false
                ReviewOrderScreen(
                    orderId = orderId,
                    editMode = editMode,
                    navController = innerNavController
                )
            }

            composable(
                route = Routes.OrderPayment.route,
                arguments = listOf(
                    navArgument("orderId") { type = NavType.StringType },
                    navArgument("paymentType") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val orderId = backStackEntry.arguments?.getString("orderId") ?: ""
                OrderPaymentScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onPaySuccess = { id ->
                        innerNavController.popBackStack()
                        // Reload order detail
                        innerNavController.navigate(Routes.OrderDetail.createRoute(id)) {
                            popUpTo(Routes.OrderDetail.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(
                route = Routes.BookingConfirm.route,
                arguments = listOf(
                    navArgument("institutionProjectId") { type = NavType.StringType },
                    navArgument("projectId") { type = NavType.StringType },
                    navArgument("institutionId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                BookingConfirmScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onOrderCreated = { orderId ->
                        // Navigate to order detail (skip payment for now)
                        innerNavController.navigate(
                            Routes.OrderDetail.createRoute(orderId)
                        ) {
                            popUpTo(Routes.BookingConfirm.route) { inclusive = true }
                        }
                    },
                    onSelectCouponClick = { originalPrice ->
                        innerNavController.navigate(Routes.CouponList.createRoute(originalPrice))
                    }
                )
            }

            // ── Profile sub-screens ─────────────────────────────────────────────

            composable(Routes.Settings.route) {
                SettingsScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onLogout = onLogout,
                    onAccountDeleted = onAccountDeleted
                )
            }

            composable(Routes.EditProfile.route) {
                EditProfileScreen(
                    onBackClick = { innerNavController.popBackStack() }
                )
            }

            composable(Routes.MyDiaries.route) {
                MyDiariesScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    },
                    onCreateDiaryClick = {
                        innerNavController.navigate(Routes.PublishDiary.createRoute())
                    },
                    onEditDiaryClick = { id ->
                        innerNavController.navigate(Routes.PublishDiary.createRoute(id))
                    }
                )
            }

            composable(Routes.Favorites.route) {
                FavoritesScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onProjectClick = { id ->
                        innerNavController.navigate(Routes.ProjectDetail.createRoute(id))
                    },
                    onInstitutionClick = { id ->
                        innerNavController.navigate(Routes.InstitutionDetail.createRoute(id))
                    },
                    onDoctorClick = { id ->
                        innerNavController.navigate(Routes.DoctorDetail.createRoute(id))
                    },
                    onDiaryClick = { id ->
                        innerNavController.navigate(Routes.DiaryDetail.createRoute(id))
                    },
                    onArticleClick = { id ->
                        innerNavController.navigate(Routes.ArticleDetail.createRoute(id))
                    }
                )
            }

            composable(Routes.CustomerService.route) {
                CustomerServiceScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onOnlineChatClick = {
                        innerNavController.navigate(Routes.DmChat.createRoute("CS", "cs"))
                    }
                )
            }

            composable(Routes.HelpFeedback.route) {
                HelpFeedbackScreen(
                    onBackClick = { innerNavController.popBackStack() }
                )
            }

            composable(Routes.AboutUs.route) {
                AboutUsScreen(
                    onBackClick = { innerNavController.popBackStack() }
                )
            }

            composable(Routes.AccountSecurity.route) {
                val authViewModel: AuthViewModel = hiltViewModel()
                val savedAccounts by authViewModel.savedAccounts.collectAsState()
                val switchState by authViewModel.switchState.collectAsState()
                val profileViewModel: ProfileViewModel = hiltViewModel()
                val currentUser by profileViewModel.user.collectAsState()

                // 监听切换状态
                LaunchedEffect(switchState) {
                    when (switchState) {
                        is AuthUiState.Success -> {
                            profileViewModel.loadUser()
                            innerNavController.popBackStack()
                            authViewModel.resetStates()
                        }
                        is AuthUiState.Error -> {
                            authViewModel.resetStates()
                        }
                        else -> {}
                    }
                }

                AccountSecurityScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onChangePasswordClick = {
                        innerNavController.navigate(Routes.ChangePassword.route)
                    },
                    onBindPhoneClick = {
                        innerNavController.navigate(Routes.BindPhone.route)
                    },
                    onBindEmailClick = {
                        innerNavController.navigate(Routes.BindEmail.route)
                    },
                    onOfficialVerificationClick = {
                        innerNavController.navigate(Routes.OfficialVerification.route)
                    },
                    savedAccounts = savedAccounts,
                    currentPhone = currentUser?.phone ?: "",
                    onSwitchAccount = { account -> authViewModel.switchAccount(account) },
                    onRemoveAccount = { phone -> authViewModel.removeSavedAccount(phone) },
                    onAddNewAccount = { onLogout() },
                    isSwitching = switchState is AuthUiState.Loading,
                    onAccountDeleted = onAccountDeleted
                )
            }

            composable(Routes.OfficialVerification.route) { backStackEntry ->
                val refreshKey by backStackEntry.savedStateHandle
                    .getStateFlow("identityRefresh", 0L)
                    .collectAsState()
                OfficialVerificationScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onApplyClick = { roleCode ->
                        innerNavController.navigate(Routes.IdentityApplication.createRoute(roleCode))
                    },
                    refreshKey = refreshKey
                )
            }

            composable(
                route = Routes.IdentityApplication.route,
                arguments = listOf(navArgument("roleCode") { type = NavType.StringType })
            ) { backStackEntry ->
                val roleCode = backStackEntry.arguments?.getString("roleCode").orEmpty()
                IdentityApplicationScreen(
                    roleCode = roleCode,
                    onBackClick = { innerNavController.popBackStack() },
                    onSubmitted = {
                        innerNavController.previousBackStackEntry?.savedStateHandle?.set(
                            "identityRefresh",
                            System.currentTimeMillis()
                        )
                        innerNavController.popBackStack()
                    }
                )
            }

            composable(Routes.ChangePassword.route) {
                val profileViewModel: ProfileViewModel = hiltViewModel()
                val currentUser by profileViewModel.user.collectAsState()
                val hasPassword = currentUser?.hasPassword ?: true
                ChangePasswordScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onPasswordChanged = { innerNavController.popBackStack() },
                    hasPassword = hasPassword,
                    onForgotPasswordClick = { innerNavController.navigate(Routes.ResetPassword.route) }
                )
            }

            composable(Routes.ResetPassword.route) {
                ResetPasswordScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onResetSuccess = { innerNavController.popBackStack() }
                )
            }

            composable(Routes.BindPhone.route) {
                BindPhoneScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onBindSuccess = { innerNavController.popBackStack() }
                )
            }

            composable(Routes.BindEmail.route) {
                BindEmailScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onBindSuccess = { innerNavController.popBackStack() }
                )
            }

            composable(
                route = Routes.PublishDiary.route,
                arguments = listOf(
                    navArgument("diaryId") {
                        type = NavType.StringType
                        defaultValue = ""
                    }
                )
            ) { backStackEntry ->
                val diaryId = backStackEntry.arguments?.getString("diaryId")?.takeIf { it.isNotEmpty() }
                PublishDiaryScreen(
                    navController = innerNavController,
                    onBackClick = { innerNavController.popBackStack() },
                    diaryId = diaryId,
                    backStackEntry = backStackEntry
                )
            }

            composable(
                route = Routes.SelectDoctor.route,
                arguments = listOf(navArgument("institutionProjectId") { type = NavType.StringType; defaultValue = "" })
            ) { backStackEntry ->
                val institutionProjectId = backStackEntry.arguments?.getString("institutionProjectId") ?: ""
                SelectDoctorScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    institutionProjectId = institutionProjectId.ifBlank { null },
                    onConfirm = { doctor ->
                        val publishEntry = innerNavController.getBackStackEntry(Routes.PublishDiary.route)
                        val json = JSONObject().apply {
                            put("id", doctor.id)
                            put("name", doctor.name)
                        }.toString()
                        publishEntry.savedStateHandle["selectedDoctor"] = json
                        innerNavController.popBackStack()
                    }
                )
            }

            composable(Routes.SelectProject.route) {
                SelectProjectScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onConfirm = { selection ->
                        val publishEntry = innerNavController.getBackStackEntry(Routes.PublishDiary.route)
                        val json = JSONObject().apply {
                            put("projectId", selection.projectId)
                            put("projectName", selection.projectName)
                            put("institutionId", selection.institutionId)
                            put("institutionName", selection.institutionName)
                            put("institutionProjectId", selection.institutionProjectId)
                            put("orderId", selection.orderId)
                            put("doctorId", selection.doctorId)
                            put("doctorName", selection.doctorName)
                        }.toString()
                        publishEntry.savedStateHandle["selectedProject"] = json
                        innerNavController.popBackStack()
                    }
                )
            }

            composable(Routes.Messages.route) {
                MessagesScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onMessageClick = { role ->
                        innerNavController.navigate(
                            Routes.AiAgent.createRoute(role = role)
                        )
                    },
                    onCsChatClick = {
                        innerNavController.navigate(Routes.DmChat.createRoute("CS", "cs"))
                    },
                    onDmClick = { targetUserId ->
                        innerNavController.navigate(Routes.DmChat.createRoute(targetUserId, "user"))
                    },
                    onAvatarClick = { userId ->
                        innerNavController.navigate(Routes.UserProfile.createRoute(userId))
                    }
                )
            }

            composable(Routes.SelectInstitution.route) {
                SelectInstitutionScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onConfirm = { selection ->
                        val publishEntry = innerNavController.getBackStackEntry(Routes.PublishDiary.route)
                        val json = JSONObject().apply {
                            put("institutionId", selection.institutionId)
                            put("institutionName", selection.institutionName)
                        }.toString()
                        publishEntry.savedStateHandle["selectedInstitution"] = json
                        innerNavController.popBackStack()
                    }
                )
            }

            // ── Coupons ──────────────────────────────────────────────────────

            composable(
                route = Routes.CouponList.route,
                arguments = listOf(
                    navArgument("originalPrice") {
                        type = NavType.StringType
                        defaultValue = "0"
                    }
                )
            ) { backStackEntry ->
                CouponListScreen(
                    onBackClick = { innerNavController.popBackStack() },
                    onCouponSelected = { coupon ->
                        // Pass selected coupon back to PaymentScreen via savedStateHandle
                        val paymentEntry = innerNavController.previousBackStackEntry
                        val json = JSONObject().apply {
                            put("id", coupon.id)
                            put("couponId", coupon.couponId)
                            put("couponName", coupon.couponName)
                            put("couponType", coupon.couponType)
                            put("discountValue", coupon.discountValue)
                            put("minAmount", coupon.minAmount)
                        }.toString()
                        paymentEntry?.savedStateHandle?.set("selectedCoupon", json)
                        innerNavController.popBackStack()
                    }
                )
            }
        }
    }
}
}

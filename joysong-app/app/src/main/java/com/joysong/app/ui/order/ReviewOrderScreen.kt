package com.joysong.app.ui.order

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.joysong.app.R
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.repository.FileRepository
import com.joysong.app.domain.repository.OrderRepository
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.StarRatingBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ReviewOrderViewModel @Inject constructor(
    val orderRepository: OrderRepository,
    val fileRepository: FileRepository
) : androidx.lifecycle.ViewModel()

@Composable
fun ReviewOrderScreen(
    orderId: String,
    navController: NavHostController,
    editMode: Boolean = false,
    viewModel: ReviewOrderViewModel = hiltViewModel()
) {
    var order by remember { mutableStateOf<Order?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var rating by remember { mutableIntStateOf(5) }
    var content by remember { mutableStateOf("") }
    val imageUris = remember { mutableStateListOf<Uri>() }
    val imageUrls = remember { mutableStateListOf<String>() }
    var isUploading by remember { mutableStateOf(false) }
    var isSubmitting by remember { mutableStateOf(false) }
    var selectedDoctorId by remember { mutableStateOf("") }
    var selectedDoctorName by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isEditMode by remember { mutableStateOf(editMode) }
    var existingReviewId by remember { mutableStateOf("") }
    var serverImageCount by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Pre-resolve stringResource values for use in non-composable lambdas
    val submitFailedRetryMsg = stringResource(R.string.submit_failed_retry)

    // Load order / existing review based on mode
    LaunchedEffect(orderId, editMode) {
        if (editMode) {
            isEditMode = true
            viewModel.orderRepository.getReviewByOrderId(orderId)
                .onSuccess { review ->
                    existingReviewId = review.id
                    rating = review.rating
                    content = review.content
                    review.images.forEach { url ->
                        imageUrls.add(url)
                        imageUris.add(Uri.parse(url))
                    }
                    serverImageCount = review.images.size
                    // Also load order info for context
                    viewModel.orderRepository.getOrderById(orderId)
                        .onSuccess {
                            order = it
                            selectedDoctorId = it.doctorId
                            selectedDoctorName = it.doctorName
                            isLoading = false
                        }
                        .onFailure {
                            isLoading = false
                        }
                }
                .onFailure {
                    errorMsg = it.message
                    isLoading = false
                }
        } else {
            viewModel.orderRepository.getOrderById(orderId)
                .onSuccess {
                    order = it
                    selectedDoctorId = it.doctorId
                    selectedDoctorName = it.doctorName
                    isLoading = false
                }
                .onFailure {
                    errorMsg = it.message
                    isLoading = false
                }
        }
    }

    // Read selected doctor from savedStateHandle (manual override if user navigates to select)
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    LaunchedEffect(savedStateHandle) {
        savedStateHandle?.getStateFlow<String?>("selectedDoctor", null)?.collect { json ->
            if (json != null) {
                try {
                    val obj = JSONObject(json)
                    selectedDoctorId = obj.optString("id", "")
                    selectedDoctorName = obj.optString("name", "")
                } catch (_: Exception) {}
                savedStateHandle.remove<String>("selectedDoctor")
            }
        }
    }

    val pickMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 6)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val remaining = 6 - imageUris.size
            val toAdd = uris.take(remaining)
            imageUris.addAll(toAdd)
            // Upload each image
            scope.launch {
                isUploading = true
                for (uri in toAdd) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            val file = File(context.cacheDir, "review_${System.currentTimeMillis()}.jpg")
                            file.outputStream().use { outputStream ->
                                inputStream.copyTo(outputStream)
                            }
                            inputStream.close()
                            viewModel.fileRepository.uploadImage(file, "review")
                                .onSuccess { url -> imageUrls.add(url) }
                        }
                    } catch (_: Exception) {}
                }
                isUploading = false
            }
        }
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.review_order),
                onBackClick = { navController.popBackStack() }
            )
        },
        bottomBar = {
            Button(
                onClick = {
                    if (isSubmitting || isUploading) return@Button
                    val currentOrder = order ?: return@Button
                    scope.launch {
                        isSubmitting = true
                        errorMsg = null
                        if (isEditMode) {
                            viewModel.orderRepository.updateReview(
                                reviewId = existingReviewId,
                                rating = rating,
                                content = content,
                                tags = "",
                                images = imageUrls.joinToString(",")
                            )
                                .onSuccess {
                                    isSubmitting = false
                                    navController.popBackStack()
                                }
                                .onFailure {
                                    isSubmitting = false
                                    errorMsg = it.message ?: submitFailedRetryMsg
                                }
                        } else {
                            viewModel.orderRepository.submitReview(
                                orderId = orderId,
                                rating = rating,
                                content = content,
                                images = imageUrls.joinToString(","),
                                targetId = currentOrder.institutionId,
                                targetType = "institution",
                                doctorId = selectedDoctorId
                            )
                                .onSuccess {
                                    isSubmitting = false
                                    navController.popBackStack()
                                }
                                .onFailure {
                                    isSubmitting = false
                                    errorMsg = it.message ?: submitFailedRetryMsg
                                }
                        }
                    }
                },
                enabled = !isSubmitting && !isUploading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
            ) {
                Text(
                    text = when {
                        isSubmitting && isEditMode -> stringResource(R.string.updating_short)
                        isSubmitting -> stringResource(R.string.submitting_short)
                        isEditMode -> stringResource(R.string.update_review_short)
                        else -> stringResource(R.string.submit_review_short)
                    },
                    color = TextOnPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        containerColor = Background
    ) { innerPadding ->
        when {
            isLoading -> LoadingIndicator()
            order == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = errorMsg ?: stringResource(R.string.order_load_failed_detail), color = TextSecondary)
                }
            }
            else -> {
                val currentOrder = order!!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    // Order info card (hidden in edit mode)
                    if (!isEditMode) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = currentOrder.projectName,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    // Star rating
                    Text(
                        text = stringResource(R.string.service_rating_label),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    StarRatingBar(
                        rating = rating,
                        onRatingChanged = { rating = it }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Review content
                    Text(
                        text = stringResource(R.string.review_content_title),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        placeholder = { Text(stringResource(R.string.review_content_placeholder)) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryDark,
                            unfocusedBorderColor = SurfaceVariant
                        )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Image upload section
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.add_review_images),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.review_image_optional_hint),
                            fontSize = 13.sp,
                            color = TextHint
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        imageUris.forEachIndexed { index, uri ->
                            Box {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(uri)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                                IconButton(
                                    onClick = {
                                        imageUris.removeAt(index)
                                        if (index < serverImageCount) {
                                            imageUrls.removeAt(index)
                                            serverImageCount--
                                        } else {
                                            val urlIndex = index - serverImageCount
                                            if (urlIndex < imageUrls.size) imageUrls.removeAt(urlIndex)
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(20.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = TextHint,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                        if (imageUris.size < 6) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, SurfaceVariant, RoundedCornerShape(8.dp))
                                    .clickable {
                                        pickMediaLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = stringResource(R.string.add_image_desc),
                                    tint = TextHint,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                    if (isUploading) {
                        Text(
                            text = stringResource(R.string.image_uploading),
                            fontSize = 12.sp,
                            color = TextHint,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Error message
                    if (errorMsg != null && !isLoading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorMsg!!,
                            fontSize = 13.sp,
                            color = androidx.compose.ui.graphics.Color(0xFFE53935)
                        )
                    }

                    // Bottom spacer for scroll padding
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}
